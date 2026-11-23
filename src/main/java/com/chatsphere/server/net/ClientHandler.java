package com.chatsphere.server.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.*;
import com.chatsphere.server.service.ChatService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One instance per TCP connection. Two threads work for it:
 *  - the READER thread (the thread that calls run()) reads one line at a time and executes the command;
 *  - the WRITER thread takes frames from the outbound queue and writes them to the socket.
 * Everybody else (other handlers, the registry) talks to this client only through send(), which just does
 * queue.offer(...) and never blocks.
 */
public final class ClientHandler implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    static final int QUEUE_CAPACITY = 1000;
    /** Sentinel placed in the queue to tell the writer "flush everything before me, then close". Compared by identity. */
    private static final String CLOSE = new String("<close>");

    private static final Set<String> KNOWN_COMMANDS = Set.of(
            Protocol.REGISTER, Protocol.LOGIN, Protocol.LOGOUT, Protocol.PING,
            Protocol.ROOM_LIST, Protocol.ROOM_CREATE, Protocol.ROOM_JOIN, Protocol.ROOM_LEAVE,
            Protocol.ROOM_MEMBERS, Protocol.USER_LIST, Protocol.HISTORY, Protocol.HISTORY_AROUND,
            Protocol.MSG, Protocol.PM, Protocol.TYPING, Protocol.MARK_READ, Protocol.SEARCH);

    private final Socket socket;
    private final ChatService service;
    private final SessionRegistry registry;
    private final String remote;
    private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean cleaned = new AtomicBoolean();

    private volatile String user;            // null until LOGIN succeeds
    private volatile boolean closing;        // no more input is processed, no more frames are queued
    private volatile boolean graceful;       // CLOSE sentinel was queued: let the writer flush before closing
    private volatile Thread writerThread;

    public ClientHandler(Socket socket, ChatService service, SessionRegistry registry) {
        this.socket = socket;
        this.service = service;
        this.registry = registry;
        this.remote = String.valueOf(socket.getRemoteSocketAddress());
    }

    // =================================================================== lifecycle
    @Override
    public void run() {
        LOG.info("Connected: " + remote);
        try {
            socket.setSoTimeout(Limits.SERVER_READ_TIMEOUT_SEC * 1000);   // silent for 45 s = disconnected
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            writerThread = Thread.ofVirtual().name("writer-" + remote).start(() -> writeLoop(out));
            readLoop(socket.getInputStream());
        } catch (SocketTimeoutException e) {
            LOG.info("Idle timeout, closing " + describe());
        } catch (IOException e) {
            if (!closing) LOG.fine("Connection ended (" + describe() + "): " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error in connection " + describe(), e);
        } finally {
            cleanup();
        }
    }

    /**
     * Bounded line reader. We never call readLine(): it would keep growing a String for as long as an attacker keeps
     * sending bytes without a newline. Here the buffer is fixed at MAX_LINE_BYTES.
     */
    private void readLoop(InputStream raw) throws IOException {
        InputStream in = new java.io.BufferedInputStream(raw, 8192);
        byte[] line = new byte[Limits.MAX_LINE_BYTES];
        int len = 0;
        while (!closing) {
            int b = in.read();
            if (b < 0) return;                                   // client closed the connection
            if (b == '\n') {
                String text = new String(line, 0, len, StandardCharsets.UTF_8);
                len = 0;
                handleLine(text);
            } else if (len == line.length) {
                LOG.warning("Line longer than " + Limits.MAX_LINE_BYTES + " bytes from " + describe() + ", closing");
                send(Frames.error("0", ErrorCode.BAD_REQUEST));
                closeAfterFlush();
                discardRestOfLine(in);
                return;
            } else {
                line[len++] = (byte) b;
            }
        }
    }

    /**
     * After an oversized line we read and throw away the rest of it (bounded) before closing. If we closed with unread
     * data in the socket, the OS would answer with a TCP reset and the client could lose the ERROR frame.
     */
    private void discardRestOfLine(InputStream in) {
        try {
            socket.setSoTimeout(2000);
            long budget = 16L * Limits.MAX_LINE_BYTES;
            int b;
            while (budget-- > 0 && (b = in.read()) >= 0 && b != '\n') { /* discard */ }
        } catch (IOException ignored) {
            // timeout or reset: we are closing anyway
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (true) {
                String frame = outbound.take();
                if (frame == CLOSE) break;
                out.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // peer is gone; the reader will notice the closed socket and run cleanup()
        } finally {
            closeSocket();
        }
    }

    /** Always runs (finally in run()). Safe to call twice. */
    private void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        closing = true;
        String u = user;
        // Only the handler that is STILL registered may announce "offline". A replaced (old) handler returns false here.
        if (u != null && registry.unregister(u, this)) {
            registry.broadcast(Frames.userStatus(u, false), u);
            LOG.info("User offline: " + u);
        }
        Thread w = writerThread;
        if (graceful && w != null) {                  // let a farewell frame (OK, ERROR, SESSION_REPLACED) go out first
            try { w.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        closeSocket();
        if (w != null) w.interrupt();
        LOG.info("Disconnected: " + describe());
    }

    private void closeSocket() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // =================================================================== outbound (used by other threads too)
    /** Queues a frame for this client. NEVER blocks: if the client is too slow (queue full) we disconnect it. */
    void send(String frame) {
        if (closing) return;
        if (!outbound.offer(frame)) {
            LOG.warning("Outbound queue full, disconnecting slow client " + describe());
            forceClose();
        }
    }

    /** Close after everything already queued has been written. */
    void closeAfterFlush() {
        if (closing) return;
        closing = true;
        if (outbound.offer(CLOSE)) graceful = true;
        else closeSocket();
    }

    /** Close immediately (used for slow clients and server shutdown). Queued frames are dropped. */
    void forceClose() {
        closing = true;
        closeSocket();
    }

    /** Called on the OLD connection when the same user logs in again somewhere else. */
    private void replaceSession() {
        LOG.info("Session replaced for " + user);
        send(Frames.sessionReplaced());
        closeAfterFlush();
    }

    /** Test hook: takes the next queued outbound frame without a writer thread. */
    String pollOutbound() { return outbound.poll(); }

    boolean isClosing() { return closing; }

    private String describe() {
        String u = user;
        return u != null ? "user " + u : remote;
    }

    // =================================================================== command handling
    private void handleLine(String line) {
        String cmd = null;
        String reqId = "0";                         // "0 if it cannot be read"
        try {
            List<String> f = Codec.decode(line);
            cmd = f.get(0);
            if (f.size() > 1) reqId = f.get(1);
            dispatch(cmd, reqId, f);
        } catch (ChatException e) {
            replyError(cmd, reqId, e.code());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error while handling " + cmd, e);   // command name only, never its content
            replyError(cmd, reqId, ErrorCode.SERVER_ERROR);
        }
    }

    private void replyError(String cmd, String reqId, ErrorCode code) {
        if (Protocol.TYPING.equals(cmd)) return;    // TYPING is never answered, not even with an error
        send(Frames.error(reqId, code));
    }

    private void dispatch(String cmd, String reqId, List<String> f) {
        if (!KNOWN_COMMANDS.contains(cmd)) throw new ChatException(ErrorCode.UNKNOWN_COMMAND);
        boolean allowedBeforeLogin = cmd.equals(Protocol.REGISTER) || cmd.equals(Protocol.LOGIN) || cmd.equals(Protocol.PING);
        if (user == null && !allowedBeforeLogin) {
            if (cmd.equals(Protocol.TYPING)) return;        // "never errors": silently ignored
            throw new ChatException(ErrorCode.NOT_LOGGED_IN);
        }
        switch (cmd) {
            case Protocol.PING -> {
                Codec.requireSize(f, 2);
                send(Frames.pong(reqId));
            }
            case Protocol.REGISTER -> {
                Codec.requireSize(f, 4);
                if (user != null) throw new ChatException(ErrorCode.BAD_REQUEST, "already logged in");
                service.register(f.get(2), f.get(3));
                send(Frames.ok(reqId));
            }
            case Protocol.LOGIN -> login(reqId, f);
            case Protocol.LOGOUT -> {
                Codec.requireSize(f, 2);
                send(Frames.ok(reqId));
                closeAfterFlush();                   // cleanup() will broadcast USER_STATUS offline
            }
            case Protocol.ROOM_LIST -> roomList(reqId, f);
            case Protocol.ROOM_CREATE -> {
                Codec.requireSize(f, 3);
                service.createRoom(user, f.get(2));
                send(Frames.ok(reqId));
            }
            case Protocol.ROOM_JOIN -> roomJoin(reqId, f);
            case Protocol.ROOM_LEAVE -> roomLeave(reqId, f);
            case Protocol.ROOM_MEMBERS -> roomMembers(reqId, f);
            case Protocol.USER_LIST -> userList(reqId, f);
            case Protocol.HISTORY -> history(reqId, f);
            case Protocol.HISTORY_AROUND -> historyAround(reqId, f);
            case Protocol.MSG -> roomMessage(reqId, f);
            case Protocol.PM -> privateMessage(reqId, f);
            case Protocol.TYPING -> typing(f);
            case Protocol.MARK_READ -> markRead(reqId, f);
            case Protocol.SEARCH -> search(reqId, f);
            default -> throw new ChatException(ErrorCode.UNKNOWN_COMMAND);
        }
    }

    private void login(String reqId, List<String> f) {
        Codec.requireSize(f, 4);
        if (user != null) throw new ChatException(ErrorCode.BAD_REQUEST, "already logged in");
        String name = f.get(2);
        service.verifyLogin(name, f.get(3));                       // throws BAD_CREDENTIALS
        user = name;
        ClientHandler old = registry.register(name, this);         // atomic: returns the connection we replace
        if (old != null && old != this) old.replaceSession();
        send(Frames.loginOk(reqId, name));
        registry.broadcast(Frames.userStatus(name, true), name);
        LOG.info("User online: " + name);
        Map<String, List<Long>> delivered = service.markAllDeliveredFor(name);
        for (Map.Entry<String, List<Long>> e : delivered.entrySet()) {
            registry.sendTo(e.getKey(), Frames.ackPush(DeliveryStatus.DELIVERED, name, e.getValue()));
        }
    }

    // =================================================================== rooms
    private void roomList(String reqId, List<String> f) {
        Codec.requireSize(f, 3);
        String scope = f.get(2);
        if (!scope.equals(Protocol.SCOPE_MINE) && !scope.equals(Protocol.SCOPE_ALL))
            throw new ChatException(ErrorCode.BAD_REQUEST, "scope");
        send(Frames.roomListResult(reqId, scope, service.listRooms(user, scope.equals(Protocol.SCOPE_MINE))));
    }

    private void roomJoin(String reqId, List<String> f) {
        Codec.requireSize(f, 3);
        String room = f.get(2);
        service.joinRoom(user, room);
        send(Frames.ok(reqId));
        pushToOnline(service.roomMembersForFanout(room), null, Frames.roomMembersChanged(room, user, true));   // joiner included
    }

    private void roomLeave(String reqId, List<String> f) {
        Codec.requireSize(f, 3);
        String room = f.get(2);
        List<String> before = service.roomMembersForFanout(room);       // taken BEFORE leaving so the leaver is told too
        service.leaveRoom(user, room);
        send(Frames.ok(reqId));
        pushToOnline(before, null, Frames.roomMembersChanged(room, user, false));
    }

    private void roomMembers(String reqId, List<String> f) {
        Codec.requireSize(f, 3);
        String room = f.get(2);
        List<MemberInfo> members = new ArrayList<>();
        for (String name : service.roomMembers(user, room)) members.add(new MemberInfo(name, registry.isOnline(name)));
        send(Frames.roomMembersResult(reqId, room, members));
    }

    private void userList(String reqId, List<String> f) {
        Codec.requireSize(f, 2);
        List<UserSummary> users = new ArrayList<>();
        for (UserSummary u : service.listUsers(user))                   // the service always says online=false: we know better
            users.add(new UserSummary(u.username(), registry.isOnline(u.username()), u.unread(), u.lastText(), u.lastTimestamp()));
        send(Frames.userListResult(reqId, users));
    }

    /** Queues a frame for each of the given users that is online, skipping exceptUser (may be null). */
    private void pushToOnline(List<String> users, String exceptUser, String frame) {
        for (String u : users) {
            if (u.equals(exceptUser)) continue;
            registry.sendTo(u, frame);
        }
    }

    // =================================================================== history and search
    private void history(String reqId, List<String> f) {
        Codec.requireSize(f, 6);
        TargetType type = Codec.toEnum(TargetType.class, f.get(2));
        String target = f.get(3);
        long beforeId = Codec.toLong(f.get(4));
        int limit = Codec.toInt(f.get(5));
        send(Frames.historyResult(reqId, type, target, service.history(user, type, target, beforeId, limit)));
    }

    private void historyAround(String reqId, List<String> f) {
        Codec.requireSize(f, 5);
        TargetType type = Codec.toEnum(TargetType.class, f.get(2));
        String target = f.get(3);
        long messageId = Codec.toLong(f.get(4));
        send(Frames.historyResult(reqId, type, target, service.historyAround(user, type, target, messageId)));
    }

    private void search(String reqId, List<String> f) {
        Codec.requireSize(f, 9);
        String keyword = f.get(2), sender = f.get(3);
        long fromTs = Codec.toLong(f.get(4)), toTs = Codec.toLong(f.get(5));
        String scope = f.get(6);
        SearchQuery q;
        if (scope.equals(Protocol.SCOPE_CURRENT)) {
            TargetType type = Codec.toEnum(TargetType.class, f.get(7));
            String target = f.get(8);
            if (target.isBlank()) throw new ChatException(ErrorCode.BAD_REQUEST, "target required for CURRENT");
            q = new SearchQuery(keyword, sender, fromTs, toTs, true, type, target);
        } else if (scope.equals(Protocol.SCOPE_ALL)) {
            q = new SearchQuery(keyword, sender, fromTs, toTs, false, null, "");
        } else {
            throw new ChatException(ErrorCode.BAD_REQUEST, "scope");
        }
        send(Frames.searchResult(reqId, service.search(user, q)));
    }

    // =================================================================== messaging
    /** Order (A4): store, reply SENT to the sender, then MSG_PUSH to every other online member. */
    private void roomMessage(String reqId, List<String> f) {
        Codec.requireSize(f, 4);
        String room = f.get(2);
        MessageRecord rec = service.saveRoomMessage(user, room, f.get(3));
        send(Frames.sent(reqId, rec.id(), rec.timestamp()));
        pushToOnline(service.roomMembersForFanout(room), user, Frames.msgPush(room, rec));
    }

    /**
     * Order (A4, mandatory): 1 store as SENT, 2 reply SENT to the sender, 3 if the recipient is online: PM_PUSH to the
     * recipient, mark DELIVERED, ACK_PUSH DELIVERED to the sender. Steps 2 and 3 are queued by this same thread, so
     * the sender can never see DELIVERED before SENT.
     */
    private void privateMessage(String reqId, List<String> f) {
        Codec.requireSize(f, 4);
        String recipient = f.get(2);
        MessageRecord rec = service.savePrivateMessage(user, recipient, f.get(3));
        send(Frames.sent(reqId, rec.id(), rec.timestamp()));
        if (registry.sendTo(recipient, Frames.pmPush(rec))) {          // false = recipient not online: stays SENT
            service.markDelivered(List.of(rec.id()));
            send(Frames.ackPush(DeliveryStatus.DELIVERED, recipient, List.of(rec.id())));
        }
    }

    /** Never answers and never errors (any problem is ignored silently). */
    private void typing(List<String> f) {
        try {
            Codec.requireSize(f, 4);
            TargetType type = Codec.toEnum(TargetType.class, f.get(2));
            String target = f.get(3);
            if (type == TargetType.ROOM) {
                List<String> members = service.roomMembersForFanout(target);
                if (members.contains(user)) pushToOnline(members, user, Frames.typingPush(TargetType.ROOM, target, user));
            } else if (!target.equals(user)) {
                registry.sendTo(target, Frames.typingPush(TargetType.PRIVATE, user, user));   // receiver sees the sender as target
            }
        } catch (RuntimeException ignored) {
            // TYPING never produces an error frame
        }
    }

    private void markRead(String reqId, List<String> f) {
        Codec.requireSize(f, 5);
        TargetType type = Codec.toEnum(TargetType.class, f.get(2));
        String target = f.get(3);
        long upTo = Codec.toLong(f.get(4));
        if (type == TargetType.PRIVATE) {
            List<Long> ids = service.markPrivateRead(user, target, upTo);
            send(Frames.ok(reqId));
            if (!ids.isEmpty()) registry.sendTo(target, Frames.ackPush(DeliveryStatus.READ, user, ids));
        } else {
            service.markRoomRead(user, target, upTo);
            send(Frames.ok(reqId));
        }
    }
}
