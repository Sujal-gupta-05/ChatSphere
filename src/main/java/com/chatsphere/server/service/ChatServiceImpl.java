package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Validation;
import com.chatsphere.server.db.Database;
import com.chatsphere.server.security.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite/JDBC implementation of {@link ChatService}. Behaves exactly like {@link InMemoryChatService}
 * (same error codes, same order of checks) but persists everything.
 *
 * <p>Thread safety: one shared connection, every public method that touches it is synchronized.
 * Password hashing (slow on purpose) runs outside the lock.
 * Every statement is a PreparedStatement; user input is never concatenated into SQL.
 */
public class ChatServiceImpl implements ChatService, AutoCloseable {

    private final Database db;

    public ChatServiceImpl(String dbFilePath) {
        this.db = new Database(dbFilePath);
    }

    /** Not part of ChatService: lets tests and ServerMain release the file. */
    @Override
    public synchronized void close() {
        db.close();
    }

    // =====================================================================================
    // accounts
    // =====================================================================================

    @Override
    public void register(String username, String password) {
        if (!Validation.isValidUsername(username)) throw new ChatException(ErrorCode.INVALID_USERNAME);
        if (!Validation.isValidPassword(password)) throw new ChatException(ErrorCode.INVALID_PASSWORD);
        String hash = PasswordHasher.hash(password); // slow: outside the lock
        insertUser(username, hash);
    }

    private synchronized void insertUser(String username, String hash) {
        db.inTransaction(c -> {
            if (userId(c, username) != null) throw new ChatException(ErrorCode.USERNAME_TAKEN);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(username, password_hash, created_at) VALUES (?, ?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, hash);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void verifyLogin(String username, String password) {
        String stored = findHash(username);
        if (stored == null || password == null) {
            PasswordHasher.dummyVerify(); // same cost whether or not the user exists
            throw new ChatException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!PasswordHasher.verify(password, stored)) throw new ChatException(ErrorCode.BAD_CREDENTIALS);
    }

    private synchronized String findHash(String username) {
        return db.inTransaction(c -> {
            if (username == null) return null;
            try (PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // =====================================================================================
    // rooms
    // =====================================================================================

    @Override
    public synchronized void createRoom(String user, String room) {
        if (!Validation.isValidRoomName(room)) throw new ChatException(ErrorCode.INVALID_ROOM_NAME);
        db.inTransaction(c -> {
            long uid = requireUser(c, user);
            if (roomId(c, room) != null) throw new ChatException(ErrorCode.ROOM_EXISTS);
            long now = System.currentTimeMillis();
            long rid;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO rooms(name, created_by, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, room);
                ps.setLong(2, uid);
                ps.setLong(3, now);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    rid = k.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO room_members(room_id, user_id, last_read_id, joined_at) VALUES (?, ?, 0, ?)")) {
                ps.setLong(1, rid);
                ps.setLong(2, uid);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void joinRoom(String user, String room) {
        db.inTransaction(c -> {
            long rid = requireRoom(c, room);
            long uid = requireUser(c, user);
            if (marker(c, rid, user) != null) return null; // already a member: idempotent
            long newest = scalarLong(c, "SELECT COALESCE(MAX(id), 0) FROM messages WHERE room_id = ?", rid);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO room_members(room_id, user_id, last_read_id, joined_at) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, rid);
                ps.setLong(2, uid);
                ps.setLong(3, newest);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void leaveRoom(String user, String room) {
        db.inTransaction(c -> {
            long rid = requireRoom(c, room);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM room_members WHERE room_id = ? AND user_id = (SELECT id FROM users WHERE username = ?)")) {
                ps.setLong(1, rid);
                ps.setString(2, user);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static final String LIST_ROOMS_SQL = """
            SELECT r.name,
                   (SELECT COUNT(*) FROM room_members cm WHERE cm.room_id = r.id) AS member_count,
                   mine.user_id IS NOT NULL AS joined,
                   CASE WHEN mine.user_id IS NULL THEN 0 ELSE
                     (SELECT COUNT(*) FROM messages x
                       WHERE x.room_id = r.id AND x.id > mine.last_read_id AND x.sender_id <> mine.user_id) END AS unread,
                   CASE WHEN mine.user_id IS NULL THEN NULL ELSE
                     (SELECT l.text FROM messages l WHERE l.room_id = r.id ORDER BY l.id DESC LIMIT 1) END AS last_text,
                   CASE WHEN mine.user_id IS NULL THEN 0 ELSE
                     COALESCE((SELECT l.ts FROM messages l WHERE l.room_id = r.id ORDER BY l.id DESC LIMIT 1), 0) END AS last_ts
              FROM rooms r
              LEFT JOIN room_members mine
                     ON mine.room_id = r.id
                    AND mine.user_id = (SELECT id FROM users WHERE username = ?)
             WHERE (? = 0 OR mine.user_id IS NOT NULL)
             ORDER BY r.name""";

    @Override
    public synchronized List<RoomSummary> listRooms(String user, boolean onlyMine) {
        return db.inTransaction(c -> {
            List<RoomSummary> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(LIST_ROOMS_SQL)) {
                ps.setString(1, user);
                ps.setInt(2, onlyMine ? 1 : 0);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new RoomSummary(rs.getString(1), rs.getInt(2), rs.getInt(3) != 0, rs.getInt(4),
                                Validation.preview(rs.getString(5)), rs.getLong(6)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public synchronized List<String> roomMembers(String user, String room) {
        return db.inTransaction(c -> {
            long rid = requireRoom(c, room);
            if (marker(c, rid, user) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            return memberNames(c, rid);
        });
    }

    @Override
    public synchronized List<String> roomMembersForFanout(String room) {
        return db.inTransaction(c -> {
            Long rid = roomId(c, room);
            return rid == null ? List.<String>of() : memberNames(c, rid);
        });
    }

    private static List<String> memberNames(Connection c, long rid) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.username FROM room_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.room_id = ? ORDER BY u.username")) {
            ps.setLong(1, rid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    // =====================================================================================
    // users
    // =====================================================================================

    private static final String LIST_USERS_SQL = """
            SELECT u.username,
                   (SELECT COUNT(*) FROM messages x
                     WHERE x.sender_id = u.id AND x.recipient_id = me.id AND x.status <> 'READ') AS unread,
                   last.text,
                   last.ts
              FROM users u
              LEFT JOIN users me ON me.username = ?
              LEFT JOIN messages last ON last.id = max(
                     COALESCE((SELECT MAX(a.id) FROM messages a WHERE a.sender_id = u.id AND a.recipient_id = me.id), 0),
                     COALESCE((SELECT MAX(b.id) FROM messages b WHERE b.sender_id = me.id AND b.recipient_id = u.id), 0))
             WHERE u.username <> ?
             ORDER BY u.username""";

    @Override
    public synchronized List<UserSummary> listUsers(String user) {
        return db.inTransaction(c -> {
            List<UserSummary> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(LIST_USERS_SQL)) {
                ps.setString(1, user);
                ps.setString(2, user);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new UserSummary(rs.getString(1), false, rs.getInt(2),
                                Validation.preview(rs.getString(3)), rs.getLong(4)));
                    }
                }
            }
            return out;
        });
    }

    // =====================================================================================
    // messaging
    // =====================================================================================

    @Override
    public synchronized MessageRecord saveRoomMessage(String sender, String room, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        return db.inTransaction(c -> {
            long rid = requireRoom(c, room);
            if (marker(c, rid, sender) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            long sid = requireUser(c, sender);
            long now = System.currentTimeMillis();
            long id = insertMessage(c, "ROOM", rid, sid, null, text, now);
            return new MessageRecord(id, sender, now, DeliveryStatus.SENT, text);
        });
    }

    @Override
    public synchronized MessageRecord savePrivateMessage(String sender, String recipient, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        if (sender != null && sender.equals(recipient)) throw new ChatException(ErrorCode.INVALID_TARGET);
        return db.inTransaction(c -> {
            Long rid = userId(c, recipient);
            Long sid = userId(c, sender);
            if (rid == null || sid == null) throw new ChatException(ErrorCode.NO_SUCH_USER);
            long now = System.currentTimeMillis();
            long id = insertMessage(c, "PRIVATE", null, sid, rid, text, now);
            return new MessageRecord(id, sender, now, DeliveryStatus.SENT, text);
        });
    }

    private static long insertMessage(Connection c, String type, Long roomId, long senderId, Long recipientId,
                                      String text, long ts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO messages(type, room_id, sender_id, recipient_id, text, ts, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SENT')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            if (roomId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, roomId);
            ps.setLong(3, senderId);
            if (recipientId == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setLong(4, recipientId);
            ps.setString(5, text);
            ps.setLong(6, ts);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    @Override
    public synchronized void markDelivered(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET status = 'DELIVERED' WHERE id = ? AND type = 'PRIVATE' AND status = 'SENT'")) {
                for (Long id : messageIds) {
                    if (id == null) continue;
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public synchronized Map<String, List<Long>> markAllDeliveredFor(String recipient) {
        return db.inTransaction(c -> {
            Map<String, List<Long>> out = new LinkedHashMap<>();
            Long rid = userId(c, recipient);
            if (rid == null) return out;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT m.id, s.username FROM messages m JOIN users s ON s.id = m.sender_id "
                            + "WHERE m.recipient_id = ? AND m.status = 'SENT' ORDER BY m.id")) {
                ps.setLong(1, rid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET status = 'DELIVERED' WHERE recipient_id = ? AND status = 'SENT'")) {
                ps.setLong(1, rid);
                ps.executeUpdate();
            }
            return out;
        });
    }

    @Override
    public synchronized List<Long> markPrivateRead(String reader, String peer, long upToId) {
        return db.inTransaction(c -> {
            List<Long> changed = new ArrayList<>();
            Long pid = requireUser(c, peer);
            Long rid = userId(c, reader);
            if (rid == null) return changed;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM messages WHERE sender_id = ? AND recipient_id = ? AND id <= ? "
                            + "AND status <> 'READ' ORDER BY id")) {
                ps.setLong(1, pid);
                ps.setLong(2, rid);
                ps.setLong(3, upToId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) changed.add(rs.getLong(1));
                }
            }
            if (!changed.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE messages SET status = 'READ' WHERE sender_id = ? AND recipient_id = ? AND id <= ? "
                                + "AND status <> 'READ'")) {
                    ps.setLong(1, pid);
                    ps.setLong(2, rid);
                    ps.setLong(3, upToId);
                    ps.executeUpdate();
                }
            }
            return changed;
        });
    }

    @Override
    public synchronized void markRoomRead(String user, String room, long upToId) {
        db.inTransaction(c -> {
            long rid = requireRoom(c, room);
            if (marker(c, rid, user) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            long newest = scalarLong(c, "SELECT COALESCE(MAX(id), 0) FROM messages WHERE room_id = ?", rid);
            long target = Math.min(upToId, newest); // never point past the newest message
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE room_members SET last_read_id = max(last_read_id, ?) "
                            + "WHERE room_id = ? AND user_id = (SELECT id FROM users WHERE username = ?)")) {
                ps.setLong(1, target);
                ps.setLong(2, rid);
                ps.setString(3, user);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // =====================================================================================
    // history
    // =====================================================================================

    /** A resolved, access-checked conversation: the WHERE fragment, its parameters and what we need for unread. */
    private record Conv(boolean room, String where, long[] params, long me, long marker, long ref) {}

    private static final String ROOM_WHERE = "m.room_id = ?";
    private static final String PRIVATE_WHERE =
            "m.type = 'PRIVATE' AND ((m.sender_id = ? AND m.recipient_id = ?) OR (m.sender_id = ? AND m.recipient_id = ?))";

    private Conv conv(Connection c, String user, TargetType type, String target) throws SQLException {
        if (type == null || target == null) throw new ChatException(ErrorCode.BAD_REQUEST);
        if (type == TargetType.ROOM) {
            long rid = requireRoom(c, target);
            Long marker = marker(c, rid, user);
            if (marker == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            long me = requireUser(c, user);
            return new Conv(true, ROOM_WHERE, new long[]{rid}, me, marker, rid);
        }
        Long me = userId(c, user);
        Long peer = userId(c, target);
        if (me == null || peer == null) throw new ChatException(ErrorCode.NO_SUCH_USER);
        return new Conv(false, PRIVATE_WHERE, new long[]{me, peer, peer, me}, me, 0, peer);
    }

    /** extra is one of a few fixed fragments (never user input); extraParams are bound. */
    private static List<MessageRecord> fetch(Connection c, Conv cv, String extra, long[] extraParams,
                                             boolean desc, int limit) throws SQLException {
        String sql = "SELECT m.id, s.username, m.ts, m.status, m.text FROM messages m "
                + "JOIN users s ON s.id = m.sender_id WHERE " + cv.where() + extra
                + " ORDER BY m.id " + (desc ? "DESC" : "ASC") + " LIMIT ?";
        List<MessageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (long p : cv.params()) ps.setLong(i++, p);
            for (long p : extraParams) ps.setLong(i++, p);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DeliveryStatus st = cv.room() ? DeliveryStatus.SENT : DeliveryStatus.valueOf(rs.getString(4));
                    out.add(new MessageRecord(rs.getLong(1), rs.getString(2), rs.getLong(3), st, rs.getString(5)));
                }
            }
        }
        return out;
    }

    private static long firstUnread(Connection c, Conv cv) throws SQLException {
        if (cv.room()) {
            return scalarLong(c, "SELECT COALESCE(MIN(id), 0) FROM messages WHERE room_id = ? AND id > ? AND sender_id <> ?",
                    cv.ref(), cv.marker(), cv.me());
        }
        return scalarLong(c, "SELECT COALESCE(MIN(id), 0) FROM messages "
                + "WHERE sender_id = ? AND recipient_id = ? AND status <> 'READ'", cv.ref(), cv.me());
    }

    @Override
    public synchronized HistoryPage history(String user, TargetType type, String target, long beforeId, int limit) {
        int lim = limit <= 0 ? Limits.HISTORY_DEFAULT : Math.min(limit, Limits.HISTORY_MAX);
        return db.inTransaction(c -> {
            Conv cv = conv(c, user, type, target);
            List<MessageRecord> rows = beforeId > 0
                    ? fetch(c, cv, " AND m.id < ?", new long[]{beforeId}, true, lim + 1)
                    : fetch(c, cv, "", new long[0], true, lim + 1);
            boolean hasMore = rows.size() > lim;
            List<MessageRecord> page = new ArrayList<>(hasMore ? rows.subList(0, lim) : rows);
            Collections.reverse(page); // ascending
            long firstUnread = beforeId > 0 ? 0 : firstUnread(c, cv);
            return new HistoryPage(page, hasMore, firstUnread);
        });
    }

    @Override
    public synchronized HistoryPage historyAround(String user, TargetType type, String target, long messageId) {
        final int w = Limits.AROUND_WINDOW;
        return db.inTransaction(c -> {
            Conv cv = conv(c, user, type, target);
            List<MessageRecord> self = fetch(c, cv, " AND m.id = ?", new long[]{messageId}, false, 1);
            if (self.isEmpty()) throw new ChatException(ErrorCode.BAD_REQUEST, "message not in conversation");
            List<MessageRecord> older = fetch(c, cv, " AND m.id < ?", new long[]{messageId}, true, w + 1);
            boolean hasMore = older.size() > w;
            List<MessageRecord> before = new ArrayList<>(hasMore ? older.subList(0, w) : older);
            Collections.reverse(before);
            List<MessageRecord> after = fetch(c, cv, " AND m.id > ?", new long[]{messageId}, false, w);
            List<MessageRecord> all = new ArrayList<>(before);
            all.add(self.get(0));
            all.addAll(after);
            return new HistoryPage(all, hasMore, firstUnread(c, cv));
        });
    }

    // =====================================================================================
    // search
    // =====================================================================================

    @Override
    public synchronized List<SearchHit> search(String user, SearchQuery q) {
        if (q == null || q.keyword() == null || q.keyword().isBlank()) throw new ChatException(ErrorCode.BAD_REQUEST);
        if (q.currentOnly() && (q.targetType() == null || q.target() == null || q.target().isBlank())) {
            throw new ChatException(ErrorCode.BAD_REQUEST);
        }
        return db.inTransaction(c -> {
            Long me = userId(c, user);
            if (me == null) return List.<SearchHit>of();

            StringBuilder sql = new StringBuilder("""
                    SELECT m.id, m.type, r.name, s.username, rc.username, m.ts, m.text
                      FROM messages m
                      JOIN users s ON s.id = m.sender_id
                      LEFT JOIN rooms r ON r.id = m.room_id
                      LEFT JOIN users rc ON rc.id = m.recipient_id
                     WHERE ((m.type = 'ROOM' AND EXISTS (SELECT 1 FROM room_members rm
                                                          WHERE rm.room_id = m.room_id AND rm.user_id = ?))
                         OR (m.type = 'PRIVATE' AND (m.sender_id = ? OR m.recipient_id = ?)))
                       AND m.text LIKE ? ESCAPE '\\'""");
            List<Object> args = new ArrayList<>(List.of(me, me, me, "%" + escapeLike(q.keyword()) + "%"));

            if (q.sender() != null && !q.sender().isEmpty()) {
                sql.append(" AND s.username = ?");
                args.add(q.sender());
            }
            if (q.fromTs() > 0) {
                sql.append(" AND m.ts >= ?");
                args.add(q.fromTs());
            }
            if (q.toTs() > 0) {
                sql.append(" AND m.ts <= ?");
                args.add(q.toTs());
            }
            if (q.currentOnly()) {
                if (q.targetType() == TargetType.ROOM) {
                    sql.append(" AND m.type = 'ROOM' AND r.name = ?");
                    args.add(q.target());
                } else {
                    Long peer = userId(c, q.target());
                    if (peer == null) return List.<SearchHit>of();
                    sql.append(" AND m.type = 'PRIVATE' AND ((m.sender_id = ? AND m.recipient_id = ?)"
                            + " OR (m.sender_id = ? AND m.recipient_id = ?))");
                    args.add(me);
                    args.add(peer);
                    args.add(peer);
                    args.add(me);
                }
            }
            sql.append(" ORDER BY m.id DESC LIMIT ?");
            args.add((long) Limits.SEARCH_MAX_RESULTS);

            List<SearchHit> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    Object a = args.get(i);
                    if (a instanceof Long l) ps.setLong(i + 1, l); else ps.setString(i + 1, (String) a);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sender = rs.getString(4);
                        if ("ROOM".equals(rs.getString(2))) {
                            out.add(new SearchHit(rs.getLong(1), TargetType.ROOM, rs.getString(3), sender,
                                    rs.getLong(6), rs.getString(7)));
                        } else {
                            String other = sender.equals(user) ? rs.getString(5) : sender;
                            out.add(new SearchHit(rs.getLong(1), TargetType.PRIVATE, other, sender,
                                    rs.getLong(6), rs.getString(7)));
                        }
                    }
                }
            }
            return out;
        });
    }

    /** Makes %, _ and \ literal for LIKE ... ESCAPE '\'. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // =====================================================================================
    // small lookup helpers (all prepared statements)
    // =====================================================================================

    private static Long userId(Connection c, String username) throws SQLException {
        if (username == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long requireUser(Connection c, String username) throws SQLException {
        Long id = userId(c, username);
        if (id == null) throw new ChatException(ErrorCode.NO_SUCH_USER);
        return id;
    }

    private static Long roomId(Connection c, String room) throws SQLException {
        if (room == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM rooms WHERE name = ?")) {
            ps.setString(1, room);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long requireRoom(Connection c, String room) throws SQLException {
        Long id = roomId(c, room);
        if (id == null) throw new ChatException(ErrorCode.NO_SUCH_ROOM);
        return id;
    }

    /** The user's read marker in the room, or null when the user is not a member (or does not exist). */
    private static Long marker(Connection c, long roomId, String user) throws SQLException {
        if (user == null) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT m.last_read_id FROM room_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.room_id = ? AND u.username = ?")) {
            ps.setLong(1, roomId);
            ps.setString(2, user);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long scalarLong(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setLong(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
