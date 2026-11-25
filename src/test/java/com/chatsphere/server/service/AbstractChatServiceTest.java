package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The behavior contract of ChatService, written once. InMemoryChatServiceTest and SqliteChatServiceTest
 * extend it, so both implementations must pass exactly the same scenarios.
 */
public abstract class AbstractChatServiceTest {

    protected ChatService svc;

    protected abstract ChatService create() throws Exception;

    /** Optional clean-up hook (close files). */
    protected void destroy() throws Exception {}

    @BeforeEach
    void setUpService() throws Exception {
        svc = create();
    }

    @AfterEach
    void tearDownService() throws Exception {
        destroy();
    }

    // ------------------------------------------------------------------ helpers

    private static final String PW = "password1";

    private void users(String... names) {
        for (String n : names) svc.register(n, PW);
    }

    private static void assertCode(ErrorCode expected, Executable action) {
        ChatException ex = assertThrows(ChatException.class, action);
        assertEquals(expected, ex.code());
    }

    private static List<Long> ids(List<MessageRecord> list) {
        List<Long> out = new ArrayList<>();
        for (MessageRecord m : list) out.add(m.id());
        return out;
    }

    private static void assertStrictlyAscending(List<Long> ids) {
        for (int i = 1; i < ids.size(); i++) assertTrue(ids.get(i) > ids.get(i - 1), "ids must strictly increase");
    }

    private RoomSummary room(String user, String name, boolean onlyMine) {
        return svc.listRooms(user, onlyMine).stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
    }

    private UserSummary userRow(String viewer, String other) {
        return svc.listUsers(viewer).stream().filter(u -> u.username().equals(other)).findFirst().orElseThrow();
    }

    private static SearchQuery all(String keyword) {
        return new SearchQuery(keyword, "", 0, 0, false, null, "");
    }

    // ------------------------------------------------------------------ 1. accounts

    @Test
    void register_valid_duplicate_and_invalid_input() {
        svc.register("alice", PW);
        assertCode(ErrorCode.USERNAME_TAKEN, () -> svc.register("alice", PW));
        svc.register("Alice", PW); // names are case-sensitive: a different user
        assertCode(ErrorCode.INVALID_USERNAME, () -> svc.register("a!", PW));
        assertCode(ErrorCode.INVALID_USERNAME, () -> svc.register("ab", PW));
        assertCode(ErrorCode.INVALID_USERNAME, () -> svc.register("x".repeat(21), PW));
        assertCode(ErrorCode.INVALID_USERNAME, () -> svc.register(null, PW));
        assertCode(ErrorCode.INVALID_PASSWORD, () -> svc.register("bob", "short"));
        assertCode(ErrorCode.INVALID_PASSWORD, () -> svc.register("bob", "x".repeat(101)));
        assertCode(ErrorCode.INVALID_PASSWORD, () -> svc.register("bob", null));
        svc.register("bob", "x".repeat(100)); // 100 characters is allowed
    }

    @Test
    void register_checks_username_then_password_then_uniqueness() {
        svc.register("alice", PW);
        assertCode(ErrorCode.INVALID_USERNAME, () -> svc.register("a!", "short"));
        assertCode(ErrorCode.INVALID_PASSWORD, () -> svc.register("alice", "short"));
        assertCode(ErrorCode.USERNAME_TAKEN, () -> svc.register("alice", PW));
    }

    @Test
    void login_right_wrong_and_unknown() {
        svc.register("alice", PW);
        svc.verifyLogin("alice", PW);
        assertCode(ErrorCode.BAD_CREDENTIALS, () -> svc.verifyLogin("alice", "wrongpass1"));
        assertCode(ErrorCode.BAD_CREDENTIALS, () -> svc.verifyLogin("nobody", PW));
        assertCode(ErrorCode.BAD_CREDENTIALS, () -> svc.verifyLogin("Alice", PW)); // case-sensitive
        assertCode(ErrorCode.BAD_CREDENTIALS, () -> svc.verifyLogin("alice", null));
        assertCode(ErrorCode.BAD_CREDENTIALS, () -> svc.verifyLogin(null, PW));
    }

    // ------------------------------------------------------------------ 2. rooms

    @Test
    void createRoom_rules_and_listing() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        assertCode(ErrorCode.ROOM_EXISTS, () -> svc.createRoom("bob", "general"));
        assertCode(ErrorCode.INVALID_ROOM_NAME, () -> svc.createRoom("alice", "ab"));
        assertCode(ErrorCode.INVALID_ROOM_NAME, () -> svc.createRoom("alice", "bad name!"));
        assertCode(ErrorCode.INVALID_ROOM_NAME, () -> svc.createRoom("alice", null));
        assertCode(ErrorCode.NO_SUCH_USER, () -> svc.createRoom("ghost", "ghosts"));

        assertEquals(List.of("alice"), svc.roomMembers("alice", "general")); // creator is a member

        RoomSummary mine = room("alice", "general", true);
        assertNotNull(mine);
        assertTrue(mine.joined());
        assertEquals(1, mine.memberCount());
        assertEquals(0, mine.unread());
        assertEquals("", mine.lastText());
        assertEquals(0L, mine.lastTimestamp());

        assertNull(room("bob", "general", true), "not joined, so not in MINE");
        RoomSummary other = room("bob", "general", false);
        assertNotNull(other);
        assertFalse(other.joined());

        svc.joinRoom("bob", "general");
        assertEquals(2, room("alice", "general", false).memberCount());
    }

    @Test
    void listRooms_is_sorted_by_name() {
        users("alice");
        svc.createRoom("alice", "zeta");
        svc.createRoom("alice", "alpha");
        svc.createRoom("alice", "general");
        List<String> names = svc.listRooms("alice", false).stream().map(RoomSummary::name).toList();
        assertEquals(List.of("alpha", "general", "zeta"), names);
    }

    // ------------------------------------------------------------------ 3. join / leave / members

    @Test
    void join_leave_and_member_rules() {
        users("alice", "bob", "carol");
        svc.createRoom("alice", "general");

        svc.joinRoom("bob", "general");
        svc.joinRoom("bob", "general"); // twice is fine
        assertEquals(List.of("alice", "bob"), svc.roomMembers("alice", "general"));
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.joinRoom("bob", "nowhere"));

        svc.leaveRoom("carol", "general"); // not a member: still fine
        svc.leaveRoom("bob", "general");
        svc.leaveRoom("bob", "general"); // idempotent
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.leaveRoom("bob", "nowhere"));
        assertEquals(List.of("alice"), svc.roomMembers("alice", "general"));

        assertCode(ErrorCode.NOT_MEMBER, () -> svc.roomMembers("bob", "general"));
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.roomMembers("alice", "nowhere"));

        // fan-out list: no membership check, unknown room gives an empty list
        assertEquals(List.of("alice"), svc.roomMembersForFanout("general"));
        assertEquals(List.of(), svc.roomMembersForFanout("nowhere"));
    }

    @Test
    void leaving_keeps_room_and_messages_and_rejoin_shows_history() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        svc.joinRoom("bob", "general");
        svc.saveRoomMessage("alice", "general", "one");
        svc.saveRoomMessage("bob", "general", "two");
        svc.leaveRoom("bob", "general");
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.history("bob", TargetType.ROOM, "general", 0, 0));
        assertNotNull(room("bob", "general", false), "rooms are never deleted");
        svc.joinRoom("bob", "general");
        assertEquals(2, svc.history("bob", TargetType.ROOM, "general", 0, 0).messages().size());
    }

    // ------------------------------------------------------------------ 4. room messages

    @Test
    void roomMessage_rules() {
        users("alice", "bob");
        svc.createRoom("alice", "general");

        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.saveRoomMessage("alice", "general", ""));
        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.saveRoomMessage("alice", "general", "  \n\t "));
        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.saveRoomMessage("alice", "general", null));
        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.saveRoomMessage("alice", "general", "x".repeat(2001)));
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.saveRoomMessage("alice", "nowhere", "hi"));
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.saveRoomMessage("bob", "general", "hi"));
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.saveRoomMessage("ghost", "general", "hi"));

        MessageRecord a = svc.saveRoomMessage("alice", "general", "x".repeat(2000)); // exactly the limit
        MessageRecord b = svc.saveRoomMessage("alice", "general", "line one\nline two");
        MessageRecord c = svc.saveRoomMessage("alice", "general", "third");
        assertTrue(a.id() > 0);
        assertStrictlyAscending(List.of(a.id(), b.id(), c.id()));
        assertEquals(DeliveryStatus.SENT, c.status());
        assertEquals("alice", c.sender());
        assertTrue(c.timestamp() > 0);

        List<MessageRecord> stored = svc.history("alice", TargetType.ROOM, "general", 0, 0).messages();
        assertEquals("line one\nline two", stored.get(1).text(), "newlines survive storage");
    }

    // ------------------------------------------------------------------ 5. room unread

    @Test
    void roomUnread_rules() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        svc.saveRoomMessage("alice", "general", "old 1");
        svc.saveRoomMessage("alice", "general", "old 2");
        svc.joinRoom("bob", "general");
        assertEquals(0, room("bob", "general", true).unread(), "history before joining is not unread");

        svc.saveRoomMessage("bob", "general", "bob 1");
        svc.saveRoomMessage("bob", "general", "bob 2");
        MessageRecord last = svc.saveRoomMessage("alice", "general", "alice new");

        assertEquals(2, room("alice", "general", true).unread(), "only messages from others count");
        assertEquals(1, room("bob", "general", true).unread(), "own messages do not count");
        assertEquals("alice new", room("bob", "general", true).lastText());
        assertEquals(last.timestamp(), room("bob", "general", true).lastTimestamp());

        svc.markRoomRead("alice", "general", last.id());
        assertEquals(0, room("alice", "general", true).unread());

        svc.markRoomRead("alice", "general", 1); // the marker never moves backward
        assertEquals(0, room("alice", "general", true).unread());

        svc.saveRoomMessage("bob", "general", "bob 3");
        assertEquals(1, room("alice", "general", true).unread());

        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.markRoomRead("alice", "nowhere", 1));
        users("carol");
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.markRoomRead("carol", "general", 1));
    }

    @Test
    void markRoomRead_beyond_newest_does_not_hide_future_messages() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        svc.joinRoom("bob", "general");
        svc.saveRoomMessage("alice", "general", "first");
        svc.markRoomRead("bob", "general", 1_000_000);
        svc.saveRoomMessage("alice", "general", "second");
        assertEquals(1, room("bob", "general", true).unread());
    }

    @Test
    void rejoin_resets_marker_to_newest() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        svc.joinRoom("bob", "general");
        svc.leaveRoom("bob", "general");
        svc.saveRoomMessage("alice", "general", "while bob was away");
        svc.joinRoom("bob", "general");
        assertEquals(0, room("bob", "general", true).unread());
    }

    // ------------------------------------------------------------------ 6. private messages

    @Test
    void privateMessage_rules() {
        users("alice", "bob");
        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.savePrivateMessage("alice", "bob", "   "));
        assertCode(ErrorCode.INVALID_MESSAGE, () -> svc.savePrivateMessage("alice", "bob", "x".repeat(2001)));
        assertCode(ErrorCode.INVALID_TARGET, () -> svc.savePrivateMessage("alice", "alice", "me"));
        assertCode(ErrorCode.NO_SUCH_USER, () -> svc.savePrivateMessage("alice", "ghost", "hi"));
        assertCode(ErrorCode.NO_SUCH_USER, () -> svc.savePrivateMessage("ghost", "alice", "hi"));

        MessageRecord m = svc.savePrivateMessage("alice", "bob", "hello");
        assertEquals(DeliveryStatus.SENT, m.status());
        assertEquals("alice", m.sender());
    }

    @Test
    void listUsers_unread_preview_and_order() {
        users("carol", "alice", "bob");
        List<String> names = svc.listUsers("alice").stream().map(UserSummary::username).toList();
        assertEquals(List.of("bob", "carol"), names, "everyone but me, by username");
        assertTrue(svc.listUsers("alice").stream().noneMatch(UserSummary::online));
        assertEquals("", userRow("alice", "bob").lastText());
        assertEquals(0L, userRow("alice", "bob").lastTimestamp());

        svc.savePrivateMessage("alice", "bob", "first");
        MessageRecord last = svc.savePrivateMessage("alice", "bob", "Hey,\n  are   you free?");

        UserSummary seenByBob = userRow("bob", "alice");
        assertEquals(2, seenByBob.unread());
        assertEquals("Hey, are you free?", seenByBob.lastText(), "whitespace collapsed");
        assertEquals(last.timestamp(), seenByBob.lastTimestamp());

        UserSummary seenByAlice = userRow("alice", "bob");
        assertEquals(0, seenByAlice.unread(), "my own messages are never unread for me");
        assertEquals("Hey, are you free?", seenByAlice.lastText(), "preview covers both directions");

        assertEquals(0, userRow("alice", "carol").unread());
    }

    @Test
    void preview_is_cut_to_80_characters_with_ellipsis() {
        users("alice", "bob");
        svc.savePrivateMessage("alice", "bob", "x".repeat(200));
        String preview = userRow("bob", "alice").lastText();
        assertEquals(80, preview.length());
        assertTrue(preview.endsWith("\u2026"));
    }

    // ------------------------------------------------------------------ 7. delivery and read

    @Test
    void markDelivered_only_moves_sent_forward() {
        users("alice", "bob");
        MessageRecord m1 = svc.savePrivateMessage("alice", "bob", "one");
        MessageRecord m2 = svc.savePrivateMessage("alice", "bob", "two");

        svc.markDelivered(List.of(m1.id()));
        List<MessageRecord> h = svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).messages();
        assertEquals(DeliveryStatus.DELIVERED, h.get(0).status());
        assertEquals(DeliveryStatus.SENT, h.get(1).status());

        svc.markPrivateRead("bob", "alice", m1.id());
        svc.markDelivered(List.of(m1.id(), 99999L)); // READ must not go back; unknown ids ignored
        h = svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).messages();
        assertEquals(DeliveryStatus.READ, h.get(0).status());
        assertEquals(m2.id(), h.get(1).id());
        svc.markDelivered(List.of());
        svc.markDelivered(null);
    }

    @Test
    void markDelivered_ignores_room_messages() {
        users("alice");
        svc.createRoom("alice", "general");
        MessageRecord m = svc.saveRoomMessage("alice", "general", "hi");
        svc.markDelivered(List.of(m.id()));
        assertEquals(DeliveryStatus.SENT, svc.history("alice", TargetType.ROOM, "general", 0, 0).messages().get(0).status());
    }

    @Test
    void markAllDeliveredFor_returns_senders_and_ids_once() {
        users("alice", "bob", "carol");
        MessageRecord a1 = svc.savePrivateMessage("alice", "bob", "a1");
        MessageRecord a2 = svc.savePrivateMessage("alice", "bob", "a2");
        MessageRecord c1 = svc.savePrivateMessage("carol", "bob", "c1");
        svc.savePrivateMessage("bob", "alice", "not for bob");

        Map<String, List<Long>> changed = svc.markAllDeliveredFor("bob");
        assertEquals(Set.of("alice", "carol"), changed.keySet());
        assertEquals(List.of(a1.id(), a2.id()), changed.get("alice"));
        assertEquals(List.of(c1.id()), changed.get("carol"));
        assertTrue(svc.markAllDeliveredFor("bob").isEmpty(), "second call changes nothing");
        assertTrue(svc.markAllDeliveredFor("nobody").isEmpty());

        // bob's own message to alice is untouched
        List<MessageRecord> h = svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).messages();
        assertEquals(DeliveryStatus.DELIVERED, h.get(0).status());
        assertEquals(DeliveryStatus.SENT, h.get(2).status());
    }

    @Test
    void markPrivateRead_changes_only_what_it_should() {
        users("alice", "bob");
        MessageRecord m1 = svc.savePrivateMessage("alice", "bob", "1");
        MessageRecord m2 = svc.savePrivateMessage("alice", "bob", "2");
        MessageRecord m3 = svc.savePrivateMessage("alice", "bob", "3");
        MessageRecord m4 = svc.savePrivateMessage("bob", "alice", "4");

        assertEquals(List.of(m1.id(), m2.id()), svc.markPrivateRead("bob", "alice", m2.id()), "respects upToId");
        assertEquals(List.of(), svc.markPrivateRead("bob", "alice", m2.id()), "only changed ids are returned");
        assertEquals(List.of(m3.id()), svc.markPrivateRead("bob", "alice", Long.MAX_VALUE));
        assertEquals(List.of(m4.id()), svc.markPrivateRead("alice", "bob", Long.MAX_VALUE), "other direction");
        assertEquals(List.of(), svc.markPrivateRead("ghost", "alice", 100));

        for (MessageRecord m : svc.history("bob", TargetType.PRIVATE, "alice", 0, 0).messages()) {
            assertEquals(DeliveryStatus.READ, m.status());
        }
        assertEquals(0, userRow("bob", "alice").unread());
    }

    @Test
    void markPrivateRead_can_go_straight_from_sent_to_read() {
        users("alice", "bob");
        MessageRecord m = svc.savePrivateMessage("alice", "bob", "x");
        assertEquals(List.of(m.id()), svc.markPrivateRead("bob", "alice", m.id()));
    }

    // ------------------------------------------------------------------ 8. history

    private List<Long> fill(String sender, String room, int n) {
        List<Long> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(svc.saveRoomMessage(sender, room, "message " + i).id());
        return out;
    }

    @Test
    void history_newest_page_and_paging_without_gaps() {
        users("alice");
        svc.createRoom("alice", "general");
        List<Long> all = fill("alice", "general", 120);

        HistoryPage first = svc.history("alice", TargetType.ROOM, "general", 0, 0);
        assertEquals(50, first.messages().size(), "limit 0 means the default of 50");
        assertTrue(first.hasMore());
        List<Long> firstIds = ids(first.messages());
        assertStrictlyAscending(firstIds);
        assertEquals(all.get(119), firstIds.get(49));
        assertEquals(all.get(70), firstIds.get(0));

        // walk backwards through the whole conversation
        List<Long> collected = new ArrayList<>(firstIds);
        HistoryPage page = first;
        int guard = 0;
        while (page.hasMore() && guard++ < 10) {
            long oldestLoaded = page.messages().get(0).id();
            page = svc.history("alice", TargetType.ROOM, "general", oldestLoaded, 50);
            assertEquals(0L, page.firstUnreadId(), "firstUnreadId only for beforeId == 0");
            collected.addAll(0, ids(page.messages()));
        }
        assertEquals(all, collected, "no gaps and no duplicates");
        assertFalse(page.hasMore());
    }

    @Test
    void history_limit_clamping() {
        users("alice");
        svc.createRoom("alice", "general");
        fill("alice", "general", 130);
        assertEquals(50, svc.history("alice", TargetType.ROOM, "general", 0, 0).messages().size());
        assertEquals(50, svc.history("alice", TargetType.ROOM, "general", 0, -5).messages().size());
        assertEquals(100, svc.history("alice", TargetType.ROOM, "general", 0, 500).messages().size());
        assertEquals(10, svc.history("alice", TargetType.ROOM, "general", 0, 10).messages().size());
        HistoryPage exact = svc.history("alice", TargetType.ROOM, "general", 0, 100);
        assertTrue(exact.hasMore());
    }

    @Test
    void history_has_no_more_when_everything_fits() {
        users("alice");
        svc.createRoom("alice", "general");
        HistoryPage empty = svc.history("alice", TargetType.ROOM, "general", 0, 0);
        assertTrue(empty.messages().isEmpty());
        assertFalse(empty.hasMore());
        assertEquals(0L, empty.firstUnreadId());
        fill("alice", "general", 50);
        HistoryPage page = svc.history("alice", TargetType.ROOM, "general", 0, 50);
        assertEquals(50, page.messages().size());
        assertFalse(page.hasMore(), "exactly 50 messages and limit 50: nothing older");
    }

    @Test
    void history_access_rules() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.history("bob", TargetType.ROOM, "general", 0, 0));
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.history("alice", TargetType.ROOM, "nowhere", 0, 0));
        assertCode(ErrorCode.NO_SUCH_USER, () -> svc.history("alice", TargetType.PRIVATE, "ghost", 0, 0));
        assertTrue(svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).messages().isEmpty());
    }

    @Test
    void history_private_contains_both_directions_ascending() {
        users("alice", "bob", "carol");
        MessageRecord m1 = svc.savePrivateMessage("alice", "bob", "1");
        MessageRecord m2 = svc.savePrivateMessage("bob", "alice", "2");
        svc.savePrivateMessage("carol", "bob", "not in this chat");
        MessageRecord m3 = svc.savePrivateMessage("alice", "bob", "3");
        List<Long> expected = List.of(m1.id(), m2.id(), m3.id());
        assertEquals(expected, ids(svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).messages()));
        assertEquals(expected, ids(svc.history("bob", TargetType.PRIVATE, "alice", 0, 0).messages()));
    }

    @Test
    void history_firstUnreadId_for_rooms() {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        fill("alice", "general", 3);
        svc.joinRoom("bob", "general");
        assertEquals(0L, svc.history("bob", TargetType.ROOM, "general", 0, 0).firstUnreadId());

        long a4 = svc.saveRoomMessage("alice", "general", "new 1").id();
        MessageRecord a5 = svc.saveRoomMessage("alice", "general", "new 2");
        assertEquals(a4, svc.history("bob", TargetType.ROOM, "general", 0, 0).firstUnreadId());
        assertEquals(0L, svc.history("alice", TargetType.ROOM, "general", 0, 0).firstUnreadId(), "own messages are never unread");
        assertEquals(0L, svc.history("bob", TargetType.ROOM, "general", a5.id(), 10).firstUnreadId(), "only when beforeId == 0");

        svc.markRoomRead("bob", "general", a5.id());
        assertEquals(0L, svc.history("bob", TargetType.ROOM, "general", 0, 0).firstUnreadId());
    }

    @Test
    void history_firstUnreadId_for_private_chats() {
        users("alice", "bob");
        MessageRecord m1 = svc.savePrivateMessage("alice", "bob", "1");
        MessageRecord m2 = svc.savePrivateMessage("alice", "bob", "2");
        assertEquals(m1.id(), svc.history("bob", TargetType.PRIVATE, "alice", 0, 0).firstUnreadId());
        assertEquals(0L, svc.history("alice", TargetType.PRIVATE, "bob", 0, 0).firstUnreadId());
        svc.markPrivateRead("bob", "alice", m1.id());
        assertEquals(m2.id(), svc.history("bob", TargetType.PRIVATE, "alice", 0, 0).firstUnreadId());
        svc.markPrivateRead("bob", "alice", m2.id());
        assertEquals(0L, svc.history("bob", TargetType.PRIVATE, "alice", 0, 0).firstUnreadId());
    }

    // ------------------------------------------------------------------ 9. historyAround

    @Test
    void historyAround_window_and_hasMore() {
        users("alice");
        svc.createRoom("alice", "general");
        List<Long> all = fill("alice", "general", 120);

        HistoryPage mid = svc.historyAround("alice", TargetType.ROOM, "general", all.get(59));
        assertEquals(51, mid.messages().size());
        assertEquals(all.subList(34, 85), ids(mid.messages()));
        assertTrue(mid.hasMore());

        HistoryPage start = svc.historyAround("alice", TargetType.ROOM, "general", all.get(0));
        assertEquals(26, start.messages().size());
        assertFalse(start.hasMore());

        HistoryPage edge = svc.historyAround("alice", TargetType.ROOM, "general", all.get(25)); // exactly 25 older
        assertEquals(51, edge.messages().size());
        assertFalse(edge.hasMore());
        HistoryPage edge2 = svc.historyAround("alice", TargetType.ROOM, "general", all.get(26));
        assertTrue(edge2.hasMore());

        HistoryPage end = svc.historyAround("alice", TargetType.ROOM, "general", all.get(119));
        assertEquals(26, end.messages().size());
        assertEquals(all.get(119), end.messages().get(25).id());
    }

    @Test
    void historyAround_errors() {
        users("alice", "bob", "carol");
        svc.createRoom("alice", "general");
        long inRoom = svc.saveRoomMessage("alice", "general", "in the room").id();
        long inPm = svc.savePrivateMessage("alice", "bob", "pm").id();

        assertCode(ErrorCode.BAD_REQUEST, () -> svc.historyAround("alice", TargetType.ROOM, "general", 99999));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.historyAround("alice", TargetType.ROOM, "general", inPm));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.historyAround("alice", TargetType.PRIVATE, "bob", inRoom));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.historyAround("carol", TargetType.PRIVATE, "bob", inPm));
        assertCode(ErrorCode.NOT_MEMBER, () -> svc.historyAround("bob", TargetType.ROOM, "general", inRoom));
        assertCode(ErrorCode.NO_SUCH_ROOM, () -> svc.historyAround("alice", TargetType.ROOM, "nowhere", inRoom));
    }

    @Test
    void historyAround_fills_firstUnreadId() {
        users("alice", "bob");
        MessageRecord m1 = svc.savePrivateMessage("alice", "bob", "1");
        svc.savePrivateMessage("alice", "bob", "2");
        assertEquals(m1.id(), svc.historyAround("bob", TargetType.PRIVATE, "alice", m1.id()).firstUnreadId());
    }

    // ------------------------------------------------------------------ 10. search

    /** alice+bob in "general", carol alone in "secret", private alice-bob and bob-carol. */
    private record Corpus(long g1, long g2, long secret, long pmAliceBob, long pmBobCarol) {}

    private Corpus corpus() {
        users("alice", "bob", "carol");
        svc.createRoom("alice", "general");
        svc.joinRoom("bob", "general");
        svc.createRoom("carol", "secret");
        long g1 = svc.saveRoomMessage("alice", "general", "Find the NEEDLE here").id();
        long g2 = svc.saveRoomMessage("bob", "general", "needle in a haystack").id();
        long s1 = svc.saveRoomMessage("carol", "secret", "secret needle").id();
        long p1 = svc.savePrivateMessage("alice", "bob", "private needle").id();
        long p2 = svc.savePrivateMessage("bob", "carol", "bob and carol needle").id();
        return new Corpus(g1, g2, s1, p1, p2);
    }

    @Test
    void search_is_limited_to_my_conversations_and_newest_first() {
        Corpus c = corpus();
        List<SearchHit> hits = svc.search("alice", all("needle"));
        assertEquals(List.of(c.pmAliceBob(), c.g2(), c.g1()), hits.stream().map(SearchHit::id).toList());

        SearchHit pm = hits.get(0);
        assertEquals(TargetType.PRIVATE, pm.targetType());
        assertEquals("bob", pm.target(), "for a private hit the target is the other user");
        assertEquals("alice", pm.sender());
        assertEquals("private needle", pm.text());
        assertTrue(pm.timestamp() > 0);

        SearchHit room = hits.get(2);
        assertEquals(TargetType.ROOM, room.targetType());
        assertEquals("general", room.target());

        // bob sees alice's private message with target = alice, and his own chat with carol
        List<SearchHit> bobHits = svc.search("bob", all("needle"));
        assertEquals(4, bobHits.size());
        assertEquals("carol", bobHits.get(0).target());
        assertEquals("alice", bobHits.get(1).target());

        // carol never sees the general room or the alice-bob chat
        List<Long> carolIds = svc.search("carol", all("needle")).stream().map(SearchHit::id).toList();
        assertEquals(List.of(c.pmBobCarol(), c.secret()), carolIds);
    }

    @Test
    void search_is_case_insensitive() {
        corpus();
        assertEquals(3, svc.search("alice", all("NEEDLE")).size());
        assertEquals(3, svc.search("alice", all("nEeDlE")).size());
    }

    @Test
    void search_percent_underscore_and_backslash_are_literal() {
        users("alice");
        svc.createRoom("alice", "general");
        svc.saveRoomMessage("alice", "general", "100% sure");
        svc.saveRoomMessage("alice", "general", "100 percent");
        svc.saveRoomMessage("alice", "general", "a_b");
        svc.saveRoomMessage("alice", "general", "axb");
        svc.saveRoomMessage("alice", "general", "path C:\\temp");

        assertEquals(List.of("100% sure"), svc.search("alice", all("100%")).stream().map(SearchHit::text).toList());
        assertEquals(List.of("a_b"), svc.search("alice", all("a_b")).stream().map(SearchHit::text).toList());
        assertEquals(List.of("path C:\\temp"), svc.search("alice", all("\\temp")).stream().map(SearchHit::text).toList());
        assertEquals(1, svc.search("alice", all("%")).size());
        assertEquals(1, svc.search("alice", all("_")).size());
    }

    @Test
    void search_sender_filter() {
        corpus();
        SearchQuery q = new SearchQuery("needle", "bob", 0, 0, false, null, "");
        List<SearchHit> hits = svc.search("alice", q);
        assertEquals(1, hits.size());
        assertEquals("bob", hits.get(0).sender());
        assertTrue(svc.search("alice", new SearchQuery("needle", "carol", 0, 0, false, null, "")).isEmpty());
    }

    @Test
    void search_date_range_is_inclusive() {
        users("alice");
        svc.createRoom("alice", "general");
        MessageRecord m = svc.saveRoomMessage("alice", "general", "timed needle");
        long ts = m.timestamp();

        assertTrue(hasId(svc.search("alice", new SearchQuery("needle", "", ts, ts, false, null, "")), m.id()), "from == to == ts includes it");
        assertTrue(hasId(svc.search("alice", new SearchQuery("needle", "", ts, 0, false, null, "")), m.id()));
        assertTrue(hasId(svc.search("alice", new SearchQuery("needle", "", 0, ts, false, null, "")), m.id()));
        assertFalse(hasId(svc.search("alice", new SearchQuery("needle", "", ts + 1, 0, false, null, "")), m.id()));
        assertFalse(hasId(svc.search("alice", new SearchQuery("needle", "", 0, ts - 1, false, null, "")), m.id()));
    }

    private static boolean hasId(List<SearchHit> hits, long id) {
        return hits.stream().anyMatch(h -> h.id() == id);
    }

    @Test
    void search_current_conversation_scope() {
        Corpus c = corpus();
        List<SearchHit> room = svc.search("alice", new SearchQuery("needle", "", 0, 0, true, TargetType.ROOM, "general"));
        assertEquals(List.of(c.g2(), c.g1()), room.stream().map(SearchHit::id).toList());

        List<SearchHit> pm = svc.search("alice", new SearchQuery("needle", "", 0, 0, true, TargetType.PRIVATE, "bob"));
        assertEquals(List.of(c.pmAliceBob()), pm.stream().map(SearchHit::id).toList());
        List<SearchHit> pmFromBob = svc.search("bob", new SearchQuery("needle", "", 0, 0, true, TargetType.PRIVATE, "alice"));
        assertEquals(List.of(c.pmAliceBob()), pmFromBob.stream().map(SearchHit::id).toList());

        // a room I am not in, and a chat between two other people: nothing, no error
        assertTrue(svc.search("alice", new SearchQuery("needle", "", 0, 0, true, TargetType.ROOM, "secret")).isEmpty());
        assertTrue(svc.search("alice", new SearchQuery("needle", "", 0, 0, true, TargetType.PRIVATE, "carol")).isEmpty());
        assertTrue(svc.search("alice", new SearchQuery("needle", "", 0, 0, true, TargetType.PRIVATE, "ghost")).isEmpty());
    }

    @Test
    void search_input_errors() {
        users("alice");
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.search("alice", all("")));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.search("alice", all("   ")));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.search("alice", all(null)));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.search("alice",
                new SearchQuery("x", "", 0, 0, true, null, "")));
        assertCode(ErrorCode.BAD_REQUEST, () -> svc.search("alice",
                new SearchQuery("x", "", 0, 0, true, TargetType.ROOM, "")));
    }

    @Test
    void search_returns_at_most_100_newest_first() {
        users("alice");
        svc.createRoom("alice", "general");
        List<Long> all = fill("alice", "general", 105);
        List<SearchHit> hits = svc.search("alice", all("message"));
        assertEquals(100, hits.size());
        assertEquals(all.get(104), hits.get(0).id());
        assertEquals(all.get(5), hits.get(99).id());
        for (int i = 1; i < hits.size(); i++) assertTrue(hits.get(i).id() < hits.get(i - 1).id());
        Set<Long> unique = new HashSet<>();
        hits.forEach(h -> unique.add(h.id()));
        assertEquals(100, unique.size());
    }

    @Test
    void search_forgets_rooms_after_leaving() {
        users("alice");
        svc.createRoom("alice", "general");
        svc.saveRoomMessage("alice", "general", "needle");
        assertEquals(1, svc.search("alice", all("needle")).size());
        svc.leaveRoom("alice", "general");
        assertTrue(svc.search("alice", all("needle")).isEmpty());
    }

    @Test
    void search_for_unknown_user_is_empty() {
        corpus();
        assertTrue(svc.search("ghost", all("needle")).isEmpty());
    }

    // ------------------------------------------------------------------ concurrency smoke test

    @Test
    void concurrent_senders_get_unique_increasing_ids() throws Exception {
        users("alice", "bob");
        svc.createRoom("alice", "general");
        svc.joinRoom("bob", "general");
        int perThread = 40;
        List<Long> aliceIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> bobIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread ta = new Thread(() -> { for (int i = 0; i < perThread; i++) aliceIds.add(svc.saveRoomMessage("alice", "general", "a" + i).id()); });
        Thread tb = new Thread(() -> { for (int i = 0; i < perThread; i++) bobIds.add(svc.saveRoomMessage("bob", "general", "b" + i).id()); });
        ta.start(); tb.start(); ta.join(); tb.join();
        Set<Long> unique = new HashSet<>(aliceIds);
        unique.addAll(bobIds);
        assertEquals(perThread * 2, unique.size());
        assertStrictlyAscending(aliceIds);
        assertStrictlyAscending(bobIds);
        assertEquals(perThread * 2, svc.history("alice", TargetType.ROOM, "general", 0, 100).messages().size());
    }
}
