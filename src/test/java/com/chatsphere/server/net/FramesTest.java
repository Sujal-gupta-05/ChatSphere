package com.chatsphere.server.net;

import com.chatsphere.GoldenFrames;
import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FramesTest {

    @Test void everyServerGoldenFrameIsProducedByteForByte() {
        Map<String, String> g = GoldenFrames.serverByCommand();
        Set<String> checked = new HashSet<>();
        check(g, checked, "LOGIN_OK", Frames.loginOk("1", "demo"));
        check(g, checked, "ERROR", Frames.error("1", ErrorCode.BAD_CREDENTIALS));
        check(g, checked, "SENT", Frames.sent("7", 42, 1767225600000L));
        check(g, checked, "ROOM_LIST_RESULT", Frames.roomListResult("3", "MINE", List.of(
                new RoomSummary("general", 4, true, 2, "Welcome to ChatSphere", 1767225600000L),
                new RoomSummary("random", 2, true, 0, "", 0))));
        check(g, checked, "USER_LIST_RESULT", Frames.userListResult("4", List.of(
                new UserSummary("alice", true, 1, "Hey, are you free?", 1767225600000L),
                new UserSummary("bob", false, 0, "", 0))));
        check(g, checked, "HISTORY_RESULT", Frames.historyResult("5", TargetType.ROOM, "general", new HistoryPage(List.of(
                new MessageRecord(38, "alice", 1767225600000L, DeliveryStatus.SENT, "hello"),
                new MessageRecord(39, "bob", 1767225660000L, DeliveryStatus.SENT, "hi\nthere")), true, 38)));
        check(g, checked, "SEARCH_RESULT", Frames.searchResult("9", List.of(
                new SearchHit(38, TargetType.ROOM, "general", "alice", 1767225600000L, "hello"))));
        check(g, checked, "MSG_PUSH", Frames.msgPush("general",
                new MessageRecord(52, "bob", 1767225710000L, DeliveryStatus.SENT, "hi all")));
        check(g, checked, "PM_PUSH", Frames.pmPush(
                new MessageRecord(51, "alice", 1767225700000L, DeliveryStatus.SENT, "psst")));
        check(g, checked, "USER_STATUS", Frames.userStatus("alice", true));
        check(g, checked, "TYPING_PUSH", Frames.typingPush(TargetType.PRIVATE, "alice", "alice"));
        check(g, checked, "ACK_PUSH", Frames.ackPush(DeliveryStatus.DELIVERED, "bob", List.of(51L, 52L)));
        check(g, checked, "ROOM_MEMBERS_CHANGED", Frames.roomMembersChanged("general", "carol", true));
        check(g, checked, "SESSION_REPLACED", Frames.sessionReplaced());
        assertEquals(g.keySet(), checked, "every golden server frame must be covered");
    }

    private static void check(Map<String, String> golden, Set<String> checked, String cmd, String actual) {
        assertEquals(golden.get(cmd), actual, "frame " + cmd);
        checked.add(cmd);
    }

    @Test void simpleReplies() {
        assertEquals("OK\t5", Frames.ok("5"));
        assertEquals("PONG\t12", Frames.pong("12"));
        assertEquals("ERROR\t0\tBAD_REQUEST", Frames.error("0", ErrorCode.BAD_REQUEST));
    }

    @Test void roomMembersResultHasStrideTwo() {
        assertEquals("ROOM_MEMBERS_RESULT\t6\tgeneral\t2\talice\t1\tbob\t0",
                Frames.roomMembersResult("6", "general", List.of(new MemberInfo("alice", true), new MemberInfo("bob", false))));
    }

    @Test void emptyListsHaveCountZero() {
        assertEquals("ROOM_LIST_RESULT\t1\tALL\t0", Frames.roomListResult("1", "ALL", List.of()));
        assertEquals("USER_LIST_RESULT\t1\t0", Frames.userListResult("1", List.of()));
        assertEquals("SEARCH_RESULT\t1\t0", Frames.searchResult("1", List.of()));
        assertEquals("HISTORY_RESULT\t1\tPRIVATE\tbob\t0\t0\t0",
                Frames.historyResult("1", TargetType.PRIVATE, "bob", new HistoryPage(List.of(), false, 0)));
    }

    @Test void messageTextWithTabsNewlinesAndBackslashesStaysOnOneLine() {
        String text = "a\tb\nc\\nd";
        String f = Frames.msgPush("general", new MessageRecord(1, "bob", 5, DeliveryStatus.SENT, text));
        assertFalse(f.contains("\n"));
        assertEquals("MSG_PUSH\tgeneral\t1\tbob\t5\ta\\tb\\nc\\\\nd", f);
    }

    @Test void ackReadAndLeftVariants() {
        assertEquals("ACK_PUSH\tREAD\tbob\t1\t9", Frames.ackPush(DeliveryStatus.READ, "bob", List.of(9L)));
        assertEquals("ROOM_MEMBERS_CHANGED\tgeneral\tcarol\tLEFT", Frames.roomMembersChanged("general", "carol", false));
    }

    @Test void historyResultKeepsStatusOfEachMessage() {
        String f = Frames.historyResult("2", TargetType.PRIVATE, "bob", new HistoryPage(List.of(
                new MessageRecord(1, "me", 10, DeliveryStatus.READ, "x"),
                new MessageRecord(2, "bob", 11, DeliveryStatus.DELIVERED, "y")), false, 2));
        assertEquals("HISTORY_RESULT\t2\tPRIVATE\tbob\t0\t2\t2\t1\tme\t10\tREAD\tx\t2\tbob\t11\tDELIVERED\ty", f);
    }
}
