package com.chatsphere.server.net;

import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.server.service.InMemoryChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Milestone 2: every remaining command, pushes, ordering, presence, history, search, slow clients. */
class ServerCommandsTest {
    private ChatServer server;
    private int port;
    private final List<RawClient> clients = new ArrayList<>();

    @BeforeEach void start() throws IOException {
        server = new ChatServer(0, new InMemoryChatService());
        port = server.start();
    }

    @AfterEach void stop() {
        for (RawClient c : clients) c.close();
        server.stop();
    }

    private RawClient user(String name) throws IOException {
        RawClient c = new RawClient(port);
        clients.add(c);
        c.registerAndLogin(name, "password1");
        return c;
    }

    /** alice and bobby online; alice's "USER_STATUS|bobby|1" already consumed. */
    private RawClient[] pair() throws IOException {
        RawClient a = user("alice"), b = user("bobby");
        assertEquals("USER_STATUS|bobby|1", a.nextPipe());
        return new RawClient[]{a, b};
    }

    private static long idOf(String sentPipe) { return Long.parseLong(sentPipe.split("\\|")[2]); }

    // ------------------------------------------------------------------ rooms
    @Test void createJoinAndMembersChangedAreSentToEveryMemberIncludingTheJoiner() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "3", "general");
        assertEquals("OK|3", a.nextPipe());
        a.send(Protocol.ROOM_CREATE, "4", "general");
        assertEquals("ERROR|4|ROOM_EXISTS", a.nextPipe());
        a.send(Protocol.ROOM_CREATE, "5", "x");
        assertEquals("ERROR|5|INVALID_ROOM_NAME", a.nextPipe());
        b.send(Protocol.ROOM_JOIN, "3", "general");
        assertEquals("OK|3", b.nextPipe());
        assertEquals("ROOM_MEMBERS_CHANGED|general|bobby|JOINED", b.nextPipe());
        assertEquals("ROOM_MEMBERS_CHANGED|general|bobby|JOINED", a.nextPipe());
    }

    @Test void joiningAnUnknownRoomIsNoSuchRoom() throws Exception {
        RawClient a = user("alice");
        a.send(Protocol.ROOM_JOIN, "1", "nowhere");
        assertEquals("ERROR|1|NO_SUCH_ROOM", a.nextPipe());
    }

    @Test void leaveNotifiesRemainingMembersAndTheLeaver() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        b.send(Protocol.ROOM_LEAVE, "2", "general");
        assertEquals("OK|2", b.nextPipe());
        assertEquals("ROOM_MEMBERS_CHANGED|general|bobby|LEFT", b.nextPipe());
        assertEquals("ROOM_MEMBERS_CHANGED|general|bobby|LEFT", a.nextPipe());
    }

    @Test void roomListMineAndAllAndBadScope() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_LIST, "2", "MINE");
        assertEquals("ROOM_LIST_RESULT|2|MINE|0", b.nextPipe());
        b.send(Protocol.ROOM_LIST, "3", "ALL");
        assertEquals("ROOM_LIST_RESULT|3|ALL|1|general|1|0|0||0", b.nextPipe());
        b.send(Protocol.ROOM_LIST, "4", "SOME");
        assertEquals("ERROR|4|BAD_REQUEST", b.nextPipe());
    }

    @Test void roomMembersShowsLivePresenceFromTheRegistry() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        a.send(Protocol.ROOM_MEMBERS, "2", "general");
        assertEquals("ROOM_MEMBERS_RESULT|2|general|2|alice|1|bobby|1", a.nextPipe());
        b.close();
        assertEquals("USER_STATUS|bobby|0", a.nextPipe());
        a.send(Protocol.ROOM_MEMBERS, "3", "general");
        assertEquals("ROOM_MEMBERS_RESULT|3|general|2|alice|1|bobby|0", a.nextPipe());
    }

    @Test void roomMembersByNonMemberIsNotMember() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_MEMBERS, "2", "general");
        assertEquals("ERROR|2|NOT_MEMBER", b.nextPipe());
    }

    @Test void userListFillsInOnlineFromTheRegistry() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0];
        user("carol");                                 // carol registered and online
        a.nextPipe();                                  // USER_STATUS carol 1
        RawClient dave = new RawClient(port); clients.add(dave);
        dave.send(Protocol.REGISTER, "1", "dave", "password1"); dave.nextPipe();   // registered, never logged in
        a.send(Protocol.USER_LIST, "7");
        assertEquals("USER_LIST_RESULT|7|3|bobby|1|0||0|carol|1|0||0|dave|0|0||0", a.nextPipe());
    }

    // ------------------------------------------------------------------ room messages
    @Test void roomMessageIsStoredAckedAndPushedToOthersOnly() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        a.send(Protocol.MSG, "4", "general", "hello\nthere");
        String sent = a.nextPipe();
        assertTrue(sent.startsWith("SENT|4|"), sent);
        long id = idOf(sent);
        String push = b.nextPipe();
        assertTrue(push.startsWith("MSG_PUSH|general|" + id + "|alice|"), push);
        assertTrue(push.endsWith("|hello\\nthere"), push);
        assertTrue(a.silentFor(300), "the sender must not get his own message back");
    }

    @Test void lineBreaksBackslashNAndTabsSurviveARoundTrip() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        String tricky = "C:\\new\ttab\n\uD83D\uDE00 last";
        a.send(Protocol.MSG, "5", "general", tricky);
        a.next();
        List<String> push = b.next();
        assertEquals(Protocol.MSG_PUSH, push.get(0));
        assertEquals(tricky, push.get(5));
    }

    @Test void roomMessageErrors() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        a.send(Protocol.MSG, "2", "general", "   ");
        assertEquals("ERROR|2|INVALID_MESSAGE", a.nextPipe());
        a.send(Protocol.MSG, "3", "general", "x".repeat(2001));
        assertEquals("ERROR|3|INVALID_MESSAGE", a.nextPipe());
        a.send(Protocol.MSG, "4", "nowhere", "hi");
        assertEquals("ERROR|4|NO_SUCH_ROOM", a.nextPipe());
        b.send(Protocol.MSG, "5", "general", "hi");
        assertEquals("ERROR|5|NOT_MEMBER", b.nextPipe());
    }

    @Test void messagesArriveInTheOrderTheyWereSent() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        int n = 200;
        for (int i = 0; i < n; i++) a.send(Protocol.MSG, String.valueOf(100 + i), "general", "m" + i);
        long last = 0;
        for (int i = 0; i < n; i++) {
            List<String> sent = a.next();
            assertEquals("SENT", sent.get(0));
            assertEquals(String.valueOf(100 + i), sent.get(1));
            List<String> push = b.next();
            assertEquals("m" + i, push.get(5));
            long id = Long.parseLong(push.get(2));
            assertTrue(id > last, "ids strictly increase");
            last = id;
        }
    }

    // ------------------------------------------------------------------ private messages
    @Test void privateMessageToOnlineUserSentThenPushThenDelivered() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.PM, "5", "bobby", "psst");
        String sent = a.nextPipe();
        assertTrue(sent.startsWith("SENT|5|"), sent);
        long id = idOf(sent);
        assertEquals("ACK_PUSH|DELIVERED|bobby|1|" + id, a.nextPipe());       // SENT strictly before DELIVERED
        String push = b.nextPipe();
        assertTrue(push.startsWith("PM_PUSH|" + id + "|alice|"), push);
        assertTrue(push.endsWith("|psst"), push);
    }

    @Test void sentIsAlwaysBeforeDeliveredEvenForManyRapidMessages() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        int n = 100;
        for (int i = 0; i < n; i++) a.send(Protocol.PM, String.valueOf(10 + i), "bobby", "x" + i);
        List<Long> sentIds = new ArrayList<>();
        for (int i = 0; i < 2 * n; i++) {
            List<String> f = a.next();
            if (f.get(0).equals("SENT")) sentIds.add(Long.parseLong(f.get(2)));
            else {
                assertEquals("ACK_PUSH", f.get(0));
                assertTrue(sentIds.contains(Long.parseLong(f.get(4))), "DELIVERED for id " + f.get(4) + " arrived before its SENT");
            }
        }
        assertEquals(n, sentIds.size());
    }

    @Test void privateMessageErrors() throws Exception {
        RawClient a = user("alice");
        a.send(Protocol.PM, "1", "alice", "me");
        assertEquals("ERROR|1|INVALID_TARGET", a.nextPipe());
        a.send(Protocol.PM, "2", "ghost", "hi");
        assertEquals("ERROR|2|NO_SUCH_USER", a.nextPipe());
        a.send(Protocol.PM, "3", "ghost", " ");
        assertEquals("ERROR|3|INVALID_MESSAGE", a.nextPipe());
    }

    @Test void offlineMessageStaysSentAndIsDeliveredWhenTheRecipientLogsIn() throws Exception {
        RawClient a = user("alice");
        RawClient b0 = new RawClient(port); clients.add(b0);
        b0.send(Protocol.REGISTER, "1", "bobby", "password1"); b0.nextPipe();      // registered but offline
        a.send(Protocol.PM, "5", "bobby", "still there?");
        String sent = a.nextPipe();
        assertTrue(sent.startsWith("SENT|5|"));
        assertTrue(a.silentFor(300), "no DELIVERED while the recipient is offline");
        long id = idOf(sent);
        b0.send(Protocol.LOGIN, "2", "bobby", "password1");
        assertEquals("LOGIN_OK|2|bobby", b0.nextPipe());
        assertEquals("USER_STATUS|bobby|1", a.nextPipe());
        assertEquals("ACK_PUSH|DELIVERED|bobby|1|" + id, a.nextPipe());
    }

    @Test void markReadAcknowledgesTheSender() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.PM, "5", "bobby", "hi");
        long id = idOf(a.nextPipe());
        a.next();                                                         // ACK DELIVERED
        b.next();                                                         // PM_PUSH
        b.send(Protocol.MARK_READ, "6", "PRIVATE", "alice", String.valueOf(id));
        assertEquals("OK|6", b.nextPipe());
        assertEquals("ACK_PUSH|READ|bobby|1|" + id, a.nextPipe());
        b.send(Protocol.MARK_READ, "7", "PRIVATE", "alice", String.valueOf(id));       // nothing new: OK, no second ACK
        assertEquals("OK|7", b.nextPipe());
        assertTrue(a.silentFor(300));
    }

    @Test void markReadRoomOnlyAnswersOkAndErrorsAreMapped() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        a.send(Protocol.MARK_READ, "2", "ROOM", "general", "0");
        assertEquals("OK|2", a.nextPipe());
        b.send(Protocol.MARK_READ, "3", "ROOM", "general", "5");
        assertEquals("ERROR|3|NOT_MEMBER", b.nextPipe());
        a.send(Protocol.MARK_READ, "4", "ROOM", "nowhere", "5");
        assertEquals("ERROR|4|NO_SUCH_ROOM", a.nextPipe());
        a.send(Protocol.MARK_READ, "5", "SOMETHING", "general", "5");
        assertEquals("ERROR|5|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.MARK_READ, "6", "ROOM", "general", "abc");
        assertEquals("ERROR|6|BAD_REQUEST", a.nextPipe());
    }

    // ------------------------------------------------------------------ typing
    @Test void roomTypingReachesOtherMembersOnlyAndSenderGetsNothing() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        RawClient c = user("carol"); a.nextPipe(); b.nextPipe();           // carol online, NOT a member
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        a.send(Protocol.TYPING, "0", "ROOM", "general");
        assertEquals("TYPING_PUSH|ROOM|general|alice", b.nextPipe());
        assertTrue(a.silentFor(300));
        assertTrue(c.silentFor(300), "non-members must not see typing");
    }

    @Test void privateTypingShowsTheSenderAsTargetToTheReceiver() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.TYPING, "0", "PRIVATE", "bobby");
        assertEquals("TYPING_PUSH|PRIVATE|alice|alice", b.nextPipe());
        assertTrue(a.silentFor(300));
    }

    @Test void typingNeverProducesErrorsEvenForGarbage() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.TYPING, "0", "ROOM", "nowhere");                    // unknown room
        a.send(Protocol.TYPING, "0", "NONSENSE", "x");                      // bad type
        a.send(Protocol.TYPING, "0", "PRIVATE", "ghost");                   // offline/unknown target
        a.send(Protocol.TYPING, "0");                                       // wrong arity
        a.send(Protocol.TYPING, "0", "ROOM", "general");                    // not a member
        assertTrue(a.silentFor(400));
        a.send(Protocol.PING, "1");
        assertEquals("PONG|1", a.nextPipe());
        assertTrue(b.silentFor(100));
    }

    // ------------------------------------------------------------------ history
    @Test void historyPagesThroughAllMessagesWithoutGapsOrDuplicates() throws Exception {
        RawClient a = user("alice");
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        for (int i = 1; i <= 120; i++) { a.send(Protocol.MSG, String.valueOf(1000 + i), "general", "msg" + i); a.next(); }
        a.send(Protocol.HISTORY, "2", "ROOM", "general", "0", "50");
        List<String> page1 = a.next();
        assertEquals("HISTORY_RESULT", page1.get(0));
        assertEquals("1", page1.get(4), "hasMore");
        assertEquals("50", page1.get(6), "count");
        assertEquals("msg120", page1.get(7 + 49 * 5 + 4));
        assertEquals("msg71", page1.get(7 + 4));
        long oldest = Long.parseLong(page1.get(7));
        a.send(Protocol.HISTORY, "3", "ROOM", "general", String.valueOf(oldest), "50");
        List<String> page2 = a.next();
        assertEquals("1", page2.get(4));
        assertEquals("msg70", page2.get(7 + 49 * 5 + 4));
        oldest = Long.parseLong(page2.get(7));
        a.send(Protocol.HISTORY, "4", "ROOM", "general", String.valueOf(oldest), "50");
        List<String> page3 = a.next();
        assertEquals("0", page3.get(4), "no more history");
        assertEquals("20", page3.get(6));
        assertEquals("msg1", page3.get(7 + 4));
    }

    @Test void historyErrorsAndPrivateHistoryWithStatus() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.HISTORY, "1", "ROOM", "nowhere", "0", "50");
        assertEquals("ERROR|1|NO_SUCH_ROOM", a.nextPipe());
        a.send(Protocol.HISTORY, "2", "PRIVATE", "ghost", "0", "50");
        assertEquals("ERROR|2|NO_SUCH_USER", a.nextPipe());
        a.send(Protocol.HISTORY, "3", "ROOM", "general", "x", "50");
        assertEquals("ERROR|3|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.PM, "4", "bobby", "hi");
        long id = idOf(a.nextPipe()); a.next(); b.next();
        a.send(Protocol.HISTORY, "5", "PRIVATE", "bobby", "0", "0");
        List<String> h = a.next();
        assertEquals("PRIVATE", h.get(2));
        assertEquals("bobby", h.get(3));
        assertEquals("1", h.get(6));
        assertEquals(String.valueOf(id), h.get(7));
        assertEquals("DELIVERED", h.get(10));
    }

    @Test void historyAroundReturnsAWindowAndRejectsUnknownIds() throws Exception {
        RawClient a = user("alice");
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        long mid = 0;
        for (int i = 1; i <= 60; i++) {
            a.send(Protocol.MSG, String.valueOf(500 + i), "general", "m" + i);
            long id = Long.parseLong(a.next().get(2));
            if (i == 30) mid = id;
        }
        a.send(Protocol.HISTORY_AROUND, "2", "ROOM", "general", String.valueOf(mid));
        List<String> h = a.next();
        assertEquals("51", h.get(6), "25 before + target + 25 after");
        assertEquals("m5", h.get(7 + 4));
        a.send(Protocol.HISTORY_AROUND, "3", "ROOM", "general", "999999");
        assertEquals("ERROR|3|BAD_REQUEST", a.nextPipe());
    }

    // ------------------------------------------------------------------ search
    @Test void searchAllCurrentAndPrivacy() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        RawClient c = user("carol"); a.nextPipe(); b.nextPipe();
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        a.send(Protocol.MSG, "2", "general", "the Build is green"); a.next();
        a.send(Protocol.PM, "3", "bobby", "build me a tea"); a.next(); a.next(); b.next();
        a.send(Protocol.SEARCH, "4", "BUILD", "", "0", "0", "ALL", "", "");
        List<String> all = a.next();
        assertEquals("SEARCH_RESULT", all.get(0));
        assertEquals("2", all.get(2));
        assertEquals("PRIVATE", all.get(4));                               // newest first
        assertEquals("bobby", all.get(5));
        a.send(Protocol.SEARCH, "5", "build", "", "0", "0", "CURRENT", "ROOM", "general");
        List<String> cur = a.next();
        assertEquals("1", cur.get(2));
        assertEquals("general", cur.get(5));
        c.send(Protocol.SEARCH, "6", "build", "", "0", "0", "ALL", "", "");
        assertEquals("SEARCH_RESULT|6|0", c.nextPipe());                    // carol sees nothing of alice's chats
    }

    @Test void searchErrors() throws Exception {
        RawClient a = user("alice");
        a.send(Protocol.SEARCH, "1", " ", "", "0", "0", "ALL", "", "");
        assertEquals("ERROR|1|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.SEARCH, "2", "x", "", "0", "0", "CURRENT", "", "");
        assertEquals("ERROR|2|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.SEARCH, "3", "x", "", "0", "0", "CURRENT", "ROOM", "");
        assertEquals("ERROR|3|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.SEARCH, "4", "x", "", "0", "0", "EVERYWHERE", "", "");
        assertEquals("ERROR|4|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.SEARCH, "5", "x", "", "yesterday", "0", "ALL", "", "");
        assertEquals("ERROR|5|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.SEARCH, "6", "x");
        assertEquals("ERROR|6|BAD_REQUEST", a.nextPipe());
    }

    // ------------------------------------------------------------------ slow client protection
    /** A socket whose peer never reads: reads block, writes block, until close(). */
    static final class StuckSocket extends Socket {
        final CountDownLatch released = new CountDownLatch(1);
        final java.io.ByteArrayInputStream script;
        volatile boolean closed;
        StuckSocket(String initialInput) { script = new java.io.ByteArrayInputStream(initialInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    int b = script.read();               // first the scripted login, then block like a silent peer
                    if (b >= 0) return b;
                    await();
                    throw new IOException("closed");
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    if (script.available() > 0) return script.read(buf, off, len);   // deliver the script in one go
                    await();
                    throw new IOException("closed");
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { await(); throw new IOException("closed"); }
                @Override public void write(byte[] b, int off, int len) throws IOException { await(); throw new IOException("closed"); }
            };
        }
        private void await() { try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        @Override public void setSoTimeout(int t) { }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public synchronized void close() { closed = true; released.countDown(); }
        @Override public boolean isClosed() { return closed; }
    }

    @Test void slowClientIsDisconnectedWhenItsQueueFillsAndBroadcastsNeverBlock() throws Exception {
        StuckSocket stuck = new StuckSocket(
                "REGISTER\t1\tslowpoke\tpassword1\nLOGIN\t2\tslowpoke\tpassword1\n");
        SessionRegistry registry = new SessionRegistry();
        ClientHandler h = new ClientHandler(stuck, new InMemoryChatService(), registry);
        Thread t = Thread.ofVirtual().start(h);
        long deadline = System.currentTimeMillis() + 3000;
        while (!registry.isOnline("slowpoke") && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(registry.isOnline("slowpoke"), "scripted login should have completed");
        long start = System.nanoTime();
        for (int i = 0; i < ClientHandler.QUEUE_CAPACITY + 50; i++) registry.broadcast("frame" + i, null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 1500, "broadcasting to a stuck client must not block (took " + ms + " ms)");
        assertTrue(stuck.closed, "the slow client must have been closed");
        t.join(3000);
        assertFalse(t.isAlive(), "handler thread must end");
        assertFalse(registry.isOnline("slowpoke"), "cleanup must unregister it");
    }

    @Test void serverKeepsServingOthersWhileOneClientNeverReads() throws Exception {
        RawClient[] p = pair(); RawClient a = p[0], b = p[1];
        a.send(Protocol.ROOM_CREATE, "1", "general"); a.nextPipe();
        b.send(Protocol.ROOM_JOIN, "1", "general"); b.nextPipe(); b.nextPipe(); a.nextPipe();
        // bobby never reads again; alice floods the room with big messages
        String big = "z".repeat(1900);
        for (int i = 0; i < 3000; i++) {
            a.send(Protocol.MSG, String.valueOf(i + 10), "general", big);
            List<String> f = a.next();
            if (f.get(0).equals(Protocol.USER_STATUS)) a.next();
        }
        a.send(Protocol.PING, "9999");
        assertEquals("PONG|9999", a.nextPipe());
    }
}
