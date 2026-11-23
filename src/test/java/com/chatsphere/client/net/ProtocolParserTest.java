package com.chatsphere.client.net;

import com.chatsphere.GoldenFrames;
import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolParserTest {
    private static final Map<String, String> G = GoldenFrames.serverByCommand();

    private static List<String> golden(String cmd) { return Codec.decode(G.get(cmd)); }

    private static List<String> frame(String... fields) { return Codec.decode(Codec.encode(fields[0], List.of(fields).subList(1, fields.length))); }

    private static void assertBad(Runnable r) {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ChatException.class, r::run).code());
    }

    // ------------------------------------------------------------------ golden replies
    @Test void sent() { assertEquals(new SentAck(42, 1767225600000L), ProtocolParser.parseSent(golden("SENT"))); }

    @Test void roomList() {
        assertEquals(List.of(
                new RoomSummary("general", 4, true, 2, "Welcome to ChatSphere", 1767225600000L),
                new RoomSummary("random", 2, true, 0, "", 0)), ProtocolParser.parseRoomList(golden("ROOM_LIST_RESULT")));
    }

    @Test void userList() {
        assertEquals(List.of(
                new UserSummary("alice", true, 1, "Hey, are you free?", 1767225600000L),
                new UserSummary("bob", false, 0, "", 0)), ProtocolParser.parseUserList(golden("USER_LIST_RESULT")));
    }

    @Test void history() {
        HistoryPage p = ProtocolParser.parseHistory(golden("HISTORY_RESULT"));
        assertTrue(p.hasMore());
        assertEquals(38, p.firstUnreadId());
        assertEquals(List.of(
                new MessageRecord(38, "alice", 1767225600000L, DeliveryStatus.SENT, "hello"),
                new MessageRecord(39, "bob", 1767225660000L, DeliveryStatus.SENT, "hi\nthere")), p.messages());
    }

    @Test void search() {
        assertEquals(List.of(new SearchHit(38, TargetType.ROOM, "general", "alice", 1767225600000L, "hello")),
                ProtocolParser.parseSearch(golden("SEARCH_RESULT")));
    }

    @Test void roomMembers() {
        assertEquals(List.of(new MemberInfo("alice", true), new MemberInfo("bob", false)),
                ProtocolParser.parseRoomMembers(frame("ROOM_MEMBERS_RESULT", "6", "general", "2", "alice", "1", "bob", "0")));
    }

    @Test void emptyListsParseToEmptyLists() {
        assertEquals(List.of(), ProtocolParser.parseRoomList(frame("ROOM_LIST_RESULT", "1", "ALL", "0")));
        assertEquals(List.of(), ProtocolParser.parseUserList(frame("USER_LIST_RESULT", "1", "0")));
        assertEquals(List.of(), ProtocolParser.parseSearch(frame("SEARCH_RESULT", "1", "0")));
        assertEquals(List.of(), ProtocolParser.parseRoomMembers(frame("ROOM_MEMBERS_RESULT", "1", "r", "0")));
        HistoryPage h = ProtocolParser.parseHistory(frame("HISTORY_RESULT", "1", "ROOM", "r", "0", "0", "0"));
        assertTrue(h.messages().isEmpty());
        assertFalse(h.hasMore());
    }

    // ------------------------------------------------------------------ golden pushes
    @Test void msgPush() {
        assertEquals(new ServerEvent.RoomMessage("general", new MessageRecord(52, "bob", 1767225710000L, DeliveryStatus.SENT, "hi all")),
                ProtocolParser.parsePush(golden("MSG_PUSH")));
    }

    @Test void pmPushUsesTheSenderAsPeer() {
        assertEquals(new ServerEvent.PrivateMessage("alice", new MessageRecord(51, "alice", 1767225700000L, DeliveryStatus.DELIVERED, "psst")),
                ProtocolParser.parsePush(golden("PM_PUSH")));
    }

    @Test void userStatus() { assertEquals(new ServerEvent.UserStatus("alice", true), ProtocolParser.parsePush(golden("USER_STATUS"))); }

    @Test void typing() {
        assertEquals(new ServerEvent.Typing(TargetType.PRIVATE, "alice", "alice"), ProtocolParser.parsePush(golden("TYPING_PUSH")));
    }

    @Test void ackPush() {
        assertEquals(new ServerEvent.StatusUpdate(DeliveryStatus.DELIVERED, "bob", List.of(51L, 52L)), ProtocolParser.parsePush(golden("ACK_PUSH")));
        assertEquals(new ServerEvent.StatusUpdate(DeliveryStatus.READ, "bob", List.of(9L)),
                ProtocolParser.parsePush(frame("ACK_PUSH", "READ", "bob", "1", "9")));
    }

    @Test void roomMembersChanged() {
        assertEquals(new ServerEvent.RoomMembersChanged("general", "carol", true), ProtocolParser.parsePush(golden("ROOM_MEMBERS_CHANGED")));
        assertEquals(new ServerEvent.RoomMembersChanged("general", "carol", false),
                ProtocolParser.parsePush(frame("ROOM_MEMBERS_CHANGED", "general", "carol", "LEFT")));
    }

    @Test void sessionReplacedBecomesSessionEnded() {
        assertEquals(new ServerEvent.SessionEnded("Your account was signed in somewhere else."),
                ProtocolParser.parsePush(golden("SESSION_REPLACED")));
    }

    @Test void errorWithReqIdZeroIsAServerErrorOtherErrorsAreReplies() {
        assertEquals(new ServerEvent.ServerError(ErrorCode.BAD_REQUEST), ProtocolParser.parsePush(frame("ERROR", "0", "BAD_REQUEST")));
        assertEquals(new ServerEvent.ServerError(ErrorCode.SERVER_ERROR), ProtocolParser.parsePush(frame("ERROR", "0", "FROM_THE_FUTURE")));
        assertNull(ProtocolParser.parsePush(golden("ERROR")));
    }

    @Test void repliesAreNotPushes() {
        for (String cmd : List.of("LOGIN_OK", "SENT", "ROOM_LIST_RESULT", "USER_LIST_RESULT", "HISTORY_RESULT", "SEARCH_RESULT"))
            assertNull(ProtocolParser.parsePush(golden(cmd)), cmd);
        assertNull(ProtocolParser.parsePush(frame("OK", "1")));
        assertNull(ProtocolParser.parsePush(frame("PONG", "1")));
        assertNull(ProtocolParser.parsePush(frame("SOMETHING_NEW", "x")));
    }

    @Test void messageTextWithTabsAndBackslashesSurvives() {
        List<String> f = Codec.decode(Codec.encode("MSG_PUSH", "general", "1", "bob", "5", "a\tb\nc\\nd"));
        ServerEvent.RoomMessage e = (ServerEvent.RoomMessage) ProtocolParser.parsePush(f);
        assertEquals("a\tb\nc\\nd", e.message().text());
    }

    // ------------------------------------------------------------------ malformed input
    @Test void wrongFieldCountsAreBadRequest() {
        assertBad(() -> ProtocolParser.parsePush(frame("MSG_PUSH", "general", "1")));
        assertBad(() -> ProtocolParser.parsePush(frame("PM_PUSH", "1", "a", "2", "t", "extra")));
        assertBad(() -> ProtocolParser.parsePush(frame("USER_STATUS", "a")));
        assertBad(() -> ProtocolParser.parsePush(frame("SESSION_REPLACED", "x")));
        assertBad(() -> ProtocolParser.parseSent(frame("SENT", "1", "2")));
        assertBad(() -> ProtocolParser.parseRoomList(frame("ROOM_LIST_RESULT", "1")));
    }

    @Test void countThatDoesNotMatchTheNumberOfRecordsIsBadRequest() {
        assertBad(() -> ProtocolParser.parseUserList(frame("USER_LIST_RESULT", "1", "2", "alice", "1", "0", "", "0")));      // says 2, has 1
        assertBad(() -> ProtocolParser.parseUserList(frame("USER_LIST_RESULT", "1", "0", "alice", "1", "0", "", "0")));      // says 0, has 1
        assertBad(() -> ProtocolParser.parseUserList(frame("USER_LIST_RESULT", "1", "-1")));
        assertBad(() -> ProtocolParser.parsePush(frame("ACK_PUSH", "READ", "bob", "3", "1", "2")));
        assertBad(() -> ProtocolParser.parseSearch(frame("SEARCH_RESULT", "1", "2147483647")));                              // huge lying count
    }

    @Test void badNumbersBooleansAndEnumsAreBadRequest() {
        assertBad(() -> ProtocolParser.parseSent(frame("SENT", "1", "abc", "5")));
        assertBad(() -> ProtocolParser.parsePush(frame("USER_STATUS", "a", "yes")));
        assertBad(() -> ProtocolParser.parsePush(frame("TYPING_PUSH", "CHANNEL", "x", "y")));
        assertBad(() -> ProtocolParser.parsePush(frame("ACK_PUSH", "SENT", "bob", "0")));
        assertBad(() -> ProtocolParser.parsePush(frame("ROOM_MEMBERS_CHANGED", "r", "u", "KICKED")));
        assertBad(() -> ProtocolParser.parseHistory(frame("HISTORY_RESULT", "1", "ROOM", "r", "0", "0", "1", "1", "a", "5", "PENDING", "t")));
        assertBad(() -> ProtocolParser.parseSearch(frame("SEARCH_RESULT", "1", "1", "1", "NOWHERE", "x", "a", "5", "t")));
    }

    @Test void parsingTheWrongFrameKindIsBadRequest() {
        assertBad(() -> ProtocolParser.parseSent(golden("LOGIN_OK")));
        assertBad(() -> ProtocolParser.parseRoomList(golden("USER_LIST_RESULT")));
    }

    @Test void parserNeverThrowsAnythingButChatExceptionOnTruncatedGoldenFrames() {
        for (String line : GoldenFrames.serverFrames()) {
            List<String> f = Codec.decode(line);
            for (int cut = 1; cut < f.size(); cut++) {
                List<String> shorter = f.subList(0, cut);
                try { ProtocolParser.parsePush(shorter); } catch (ChatException ok) { /* expected */ }
                for (var fn : List.<java.util.function.Function<List<String>, Object>>of(ProtocolParser::parseSent,
                        ProtocolParser::parseRoomList, ProtocolParser::parseUserList, ProtocolParser::parseHistory,
                        ProtocolParser::parseSearch, ProtocolParser::parseRoomMembers)) {
                    try { fn.apply(shorter); } catch (ChatException ok) { /* expected */ }
                }
            }
        }
    }
}
