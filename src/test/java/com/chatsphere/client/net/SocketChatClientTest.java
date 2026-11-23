package com.chatsphere.client.net;

import com.chatsphere.GoldenFrames;
import com.chatsphere.client.net.SocketChatClient.Timing;
import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.server.net.ChatServer;
import com.chatsphere.server.service.InMemoryChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SocketChatClientTest {
    private static final char[] PW = "password1".toCharArray();

    /** Fast timings so reconnect/timeout tests finish quickly. */
    private static Timing fast() { return new Timing(200, 3000, 2000, 1000, new long[]{50, 50, 50, 50}, 50); }

    private final List<SocketChatClient> clients = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private ChatServer server;

    @AfterEach void cleanup() throws Exception {
        for (SocketChatClient c : clients) c.shutdown();
        for (AutoCloseable a : closeables) a.close();
        if (server != null) server.stop();
    }

    // ------------------------------------------------------------------ helpers
    private int startServer(InMemoryChatService svc, int port) throws IOException {
        server = new ChatServer(port, svc);
        return server.start();
    }

    private SocketChatClient newClient() { return newClient(Runnable::run, fast()); }

    private SocketChatClient newClient(Executor ui, Timing t) {
        SocketChatClient c = new SocketChatClient(ui, t);
        clients.add(c);
        return c;
    }

    private static final class Recorder {
        final List<ServerEvent> events = Collections.synchronizedList(new ArrayList<>());
        Recorder(SocketChatClient c) { c.addEventListener(events::add); }

        ServerEvent await(Predicate<ServerEvent> p, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            int from = 0;
            while (System.currentTimeMillis() < end) {
                synchronized (events) {
                    for (; from < events.size(); from++) if (p.test(events.get(from))) return events.get(from++);
                }
                Thread.sleep(10);
            }
            fail("expected event not received within " + ms + " ms; got " + events);
            return null;
        }

        boolean none(Predicate<ServerEvent> p, long ms) throws InterruptedException {
            Thread.sleep(ms);
            synchronized (events) { for (ServerEvent e : events) if (p.test(e)) return false; }
            return true;
        }

        long count(Predicate<ServerEvent> p) {
            synchronized (events) { return events.stream().filter(p).count(); }
        }

        List<ConnectionState> states() {
            synchronized (events) {
                List<ConnectionState> out = new ArrayList<>();
                for (ServerEvent e : events) if (e instanceof ServerEvent.ConnectionStateChanged s) out.add(s.state());
                return out;
            }
        }
    }

    private static Predicate<ServerEvent> state(ConnectionState s) {
        return e -> e instanceof ServerEvent.ConnectionStateChanged c && c.state() == s;
    }

    private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        try { return f.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception ex) throw ex; throw e; }
    }

    private static ErrorCode failureOf(CompletableFuture<?> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return ((ChatException) e.getCause()).code();
        }
        fail("future should have failed");
        return null;
    }

    private SocketChatClient connected(int port, Recorder[] rec) throws Exception {
        SocketChatClient c = newClient();
        Recorder r = new Recorder(c);
        if (rec != null) rec[0] = r;
        c.connect("127.0.0.1", port);
        r.await(state(ConnectionState.CONNECTED), 3000);
        return c;
    }

    private SocketChatClient loggedIn(int port, String name, Recorder[] rec) throws Exception {
        SocketChatClient c = connected(port, rec);
        try { get(c.register(name, PW)); } catch (ChatException e) { if (e.code() != ErrorCode.USERNAME_TAKEN) throw e; }
        get(c.login(name, PW));
        return c;
    }

    // ================================================================== connection state
    @Test void connectGoesConnectingThenConnected() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = connected(port, r);
        assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
        assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), r[0].states());
    }

    @Test void connectToNothingEndsOfflineWithCantReachServer() throws Exception {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { port = s.getLocalPort(); }   // free port, nobody listening
        SocketChatClient c = newClient();
        Recorder r = new Recorder(c);
        c.connect("127.0.0.1", port);
        ServerEvent e = r.await(state(ConnectionState.OFFLINE), 3000);
        assertEquals("Can't reach server", ((ServerEvent.ConnectionStateChanged) e).detail());
        assertEquals(ConnectionState.OFFLINE, c.getConnectionState());
        assertTrue(r.none(state(ConnectionState.RECONNECTING), 300), "not logged in: no automatic reconnect");
    }

    @Test void requestsWhileNotConnectedFailImmediatelyWithNotConnected() throws Exception {
        SocketChatClient c = newClient();
        assertEquals(ErrorCode.NOT_CONNECTED, failureOf(c.listUsers()));
        assertEquals(ErrorCode.NOT_CONNECTED, failureOf(c.login("alice", PW)));
        assertEquals(ErrorCode.NOT_CONNECTED, failureOf(c.sendRoomMessage("general", "hi")));
        c.sendTyping(TargetType.ROOM, "general");                     // silently ignored
    }

    @Test void connectWhileConnectedIsIgnoredAndDoesNotCreateASecondReader() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient c = connected(port, null);
        c.connect("127.0.0.1", port);
        c.connect("127.0.0.1", port);
        Thread.sleep(200);
        assertEquals(1, readerThreads());
    }

    private static long readerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(SocketChatClient.READER_THREAD_NAME) && t.isAlive()).count();
    }

    // ================================================================== real server round trips
    @Test void registerLoginAndErrors() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient c = connected(port, null);
        assertEquals(ErrorCode.INVALID_USERNAME, failureOf(c.register("ab", PW)));
        assertEquals(ErrorCode.INVALID_PASSWORD, failureOf(c.register("alice", "short".toCharArray())));
        get(c.register("alice", PW));
        assertEquals(ErrorCode.USERNAME_TAKEN, failureOf(c.register("alice", PW)));
        assertEquals(ErrorCode.BAD_CREDENTIALS, failureOf(c.login("alice", "wrongpass1".toCharArray())));
        assertEquals(ErrorCode.NOT_LOGGED_IN, failureOf(c.listUsers()));
        get(c.login("alice", PW));
        assertEquals(List.of(), get(c.listUsers()));
    }

    @Test void roomFlowBetweenTwoClientsWithEventsAndMultiLineText() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        Recorder[] ra = new Recorder[1], rb = new Recorder[1];
        SocketChatClient a = loggedIn(port, "alice", ra), b = loggedIn(port, "bobby", rb);
        ra[0].await(e -> e instanceof ServerEvent.UserStatus u && u.username().equals("bobby") && u.online(), 2000);
        get(a.createRoom("general"));
        get(b.joinRoom("general"));
        ra[0].await(e -> e instanceof ServerEvent.RoomMembersChanged m && m.user().equals("bobby") && m.joined(), 2000);

        String text = "line one\nline two with C:\\new and a tab\t and \uD83D\uDE00";
        SentAck ack = get(a.sendRoomMessage("general", text));
        assertTrue(ack.id() > 0);
        ServerEvent.RoomMessage rm = (ServerEvent.RoomMessage) rb[0].await(e -> e instanceof ServerEvent.RoomMessage, 2000);
        assertEquals("general", rm.room());
        assertEquals(text, rm.message().text());
        assertEquals("alice", rm.message().sender());
        assertEquals(ack.id(), rm.message().id());

        List<MemberInfo> members = get(a.listRoomMembers("general"));
        assertEquals(List.of(new MemberInfo("alice", true), new MemberInfo("bobby", true)), members);
        List<RoomSummary> mine = get(b.listRooms(true));
        assertEquals(1, mine.size());
        assertEquals(2, mine.get(0).memberCount());
        HistoryPage h = get(b.loadHistory(TargetType.ROOM, "general", 0, 50));
        assertEquals(text, h.messages().get(0).text());
        get(b.leaveRoom("general"));
        ra[0].await(e -> e instanceof ServerEvent.RoomMembersChanged m && !m.joined(), 2000);
    }

    @Test void sentFutureCompletesBeforeTheDeliveredEvent() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        loggedIn(port, "bobby", null);
        // A gated UI executor: while "hold" is true it only queues what the client submits, in submission order.
        BlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        boolean[] hold = {false};
        Executor gate = r -> { if (hold[0]) held.add(r); else r.run(); };
        SocketChatClient a = newClient(gate, fast());
        a.connect("127.0.0.1", port);
        assertTrue(waitFor(() -> a.getConnectionState() == ConnectionState.CONNECTED, 3000));
        get(a.register("alice", PW));
        get(a.login("alice", PW));
        List<String> order = new ArrayList<>();
        a.addEventListener(e -> { if (e instanceof ServerEvent.StatusUpdate) order.add("DELIVERED_EVENT"); });
        hold[0] = true;
        CompletableFuture<SentAck> f = a.sendPrivateMessage("bobby", "psst");
        assertTrue(waitFor(() -> held.size() >= 2, 3000), "client should have submitted the future completion and the event");
        f.thenAccept(x -> order.add("SENT_FUTURE"));
        hold[0] = false;
        Runnable r;
        while ((r = held.poll()) != null) r.run();                    // runs in the order the client submitted them
        assertEquals(List.of("SENT_FUTURE", "DELIVERED_EVENT"), order);
    }

    @Test void offlineMessageIsDeliveredWhenTheRecipientLogsInAndReadReceiptsFlowBack() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] ra = new Recorder[1];
        SocketChatClient a = loggedIn(port, "alice", ra);
        svc.register("bobby", "password1");                           // registered but offline
        SentAck ack = get(a.sendPrivateMessage("bobby", "still there?"));
        assertTrue(ra[0].none(e -> e instanceof ServerEvent.StatusUpdate, 300), "no DELIVERED while offline");

        Recorder[] rb = new Recorder[1];
        SocketChatClient b = connected(port, rb);
        get(b.login("bobby", PW));
        ServerEvent.StatusUpdate del = (ServerEvent.StatusUpdate) ra[0].await(e -> e instanceof ServerEvent.StatusUpdate, 2000);
        assertEquals(new ServerEvent.StatusUpdate(DeliveryStatus.DELIVERED, "bobby", List.of(ack.id())), del);

        List<UserSummary> users = get(b.listUsers());
        assertEquals(1, users.get(0).unread());
        get(b.markRead(TargetType.PRIVATE, "alice", ack.id()));
        ServerEvent.StatusUpdate read = (ServerEvent.StatusUpdate) ra[0].await(
                e -> e instanceof ServerEvent.StatusUpdate s && s.status() == DeliveryStatus.READ, 2000);
        assertEquals(List.of(ack.id()), read.messageIds());
        assertEquals("bobby", read.peer());
    }

    @Test void typingAndPresenceEvents() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        Recorder[] ra = new Recorder[1], rb = new Recorder[1];
        SocketChatClient a = loggedIn(port, "alice", ra), b = loggedIn(port, "bobby", rb);
        ra[0].await(e -> e instanceof ServerEvent.UserStatus u && u.username().equals("bobby") && u.online(), 2000);
        a.sendTyping(TargetType.PRIVATE, "bobby");
        assertEquals(new ServerEvent.Typing(TargetType.PRIVATE, "alice", "alice"), rb[0].await(e -> e instanceof ServerEvent.Typing, 2000));
        b.disconnect();
        ra[0].await(e -> e instanceof ServerEvent.UserStatus u && u.username().equals("bobby") && !u.online(), 2000);
    }

    @Test void searchAndHistoryAround() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient a = loggedIn(port, "alice", null);
        get(a.createRoom("general"));
        long id = get(a.sendRoomMessage("general", "the Build is green")).id();
        get(a.sendRoomMessage("general", "other"));
        List<SearchHit> all = get(a.search(new SearchQuery("BUILD", "", 0, 0, false, null, "")));
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        List<SearchHit> cur = get(a.search(new SearchQuery("build", "alice", 0, 0, true, TargetType.ROOM, "general")));
        assertEquals(1, cur.size());
        assertEquals(0, get(a.search(new SearchQuery("nomatch", "", 0, 0, false, null, ""))).size());
        assertEquals(ErrorCode.BAD_REQUEST, failureOf(a.search(new SearchQuery(" ", "", 0, 0, false, null, ""))));
        assertEquals(2, get(a.loadHistoryAround(TargetType.ROOM, "general", id)).messages().size());
    }

    @Test void oversizedRequestIsRefusedLocallyAndTheConnectionSurvives() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient a = loggedIn(port, "alice", null);
        assertEquals(ErrorCode.BAD_REQUEST, failureOf(a.search(new SearchQuery("x".repeat(70_000), "", 0, 0, false, null, ""))));
        assertEquals(ConnectionState.CONNECTED, a.getConnectionState());
        assertEquals(List.of(), get(a.listUsers()));
    }

    // ================================================================== threading contract
    @Test void nothingCompletesOrFiresUntilTheUiExecutorRunsIt() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        BlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        boolean[] hold = {false};
        Executor gate = r -> { if (hold[0]) held.add(r); else r.run(); };
        SocketChatClient a = newClient(gate, fast());
        a.connect("127.0.0.1", port);
        assertTrue(waitFor(() -> a.getConnectionState() == ConnectionState.CONNECTED, 3000));
        get(a.register("alice", PW));
        get(a.login("alice", PW));
        List<ServerEvent> seen = Collections.synchronizedList(new ArrayList<>());
        a.addEventListener(seen::add);

        hold[0] = true;
        CompletableFuture<List<UserSummary>> f = a.listUsers();         // reply will arrive from the server
        loggedIn(port, "bobby", null);                                   // makes the server push USER_STATUS to alice
        assertTrue(waitFor(() -> held.size() >= 2, 3000), "client must have handed work to the UI executor");
        Thread.sleep(200);
        assertFalse(f.isDone(), "future must not complete before the UI executor runs it");
        assertTrue(seen.isEmpty(), "listener must not run before the UI executor runs it");

        hold[0] = false;
        Runnable r;
        while ((r = held.poll()) != null) r.run();
        assertTrue(f.isDone());
        assertTrue(seen.stream().anyMatch(e -> e instanceof ServerEvent.UserStatus));
    }

    @Test void everyUiCallbackRunsOnTheUiThread() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        ExecutorService uiPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-ui-thread"));
        closeables.add(uiPool::shutdownNow);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        Executor ui = r -> uiPool.execute(() -> { threads.add(Thread.currentThread().getName()); r.run(); });
        SocketChatClient c = newClient(ui, fast());
        c.addEventListener(e -> assertEquals("fake-ui-thread", Thread.currentThread().getName()));
        c.connect("127.0.0.1", port);
        assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
        get(c.register("alice", PW));
        failureOf(c.login("alice", "wrongpass1".toCharArray()));
        get(c.login("alice", PW));
        get(c.listUsers());
        assertEquals(Set.of("fake-ui-thread"), threads);
    }

    @Test void severalListenersAndUnsubscribe() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient c = newClient();
        AtomicInteger one = new AtomicInteger(), two = new AtomicInteger();
        Runnable off1 = c.addEventListener(e -> one.incrementAndGet());
        c.addEventListener(e -> two.incrementAndGet());
        c.addEventListener(e -> { throw new IllegalStateException("a broken listener must not hurt the others"); });
        c.connect("127.0.0.1", port);
        assertTrue(waitFor(() -> two.get() >= 2, 3000));
        assertEquals(one.get(), two.get());
        off1.run();
        int before = one.get();
        c.disconnect();
        assertTrue(waitFor(() -> two.get() > before, 2000));
        assertEquals(before, one.get(), "unsubscribed listener must stop receiving events");
    }

    @Test void manyThreadsSendingAtOnceNeverCorruptFrames() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        try (FakeServer fs = new FakeServer((s, f) -> {
            received.add(String.join("|", f));
            FakeServer.send(s, FakeServer.frame(Protocol.SENT, f.get(1), "1", "2"));
        })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 5000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            int threads = 20, each = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            closeables.add(pool::shutdownNow);
            List<CompletableFuture<SentAck>> futures = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch go = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                int tt = t;
                pool.execute(() -> {
                    try { go.await(); } catch (InterruptedException e) { return; }
                    for (int i = 0; i < each; i++) futures.add(c.sendRoomMessage("general", "t" + tt + "-m" + i + "\nwith newline"));
                });
            }
            go.countDown();
            assertTrue(waitFor(() -> futures.size() == threads * each, 5000));
            for (CompletableFuture<SentAck> f : new ArrayList<>(futures)) assertEquals(1L, get(f).id());
            assertEquals(threads * each, received.size());
            Set<String> ids = new java.util.HashSet<>();
            for (String r : received) { assertTrue(r.startsWith("MSG|"), r); ids.add(r.split("\\|")[1]); }
            assertEquals(threads * each, ids.size(), "every request id is unique");
        }
    }

    // ================================================================== heartbeat, timeouts, drops
    @Test void pingIsSentRegularly() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> FakeServer.send(s, FakeServer.frame(Protocol.PONG, f.get(1))))) {
            SocketChatClient c = newClient(Runnable::run, new Timing(100, 5000, 2000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            int pings = 0;
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end && pings < 4) {
                String l = fs.nextLine(200);
                if (l != null && l.startsWith("PING\t")) pings++;
            }
            assertTrue(pings >= 4, "pings seen: " + pings);
        }
    }

    @Test void defaultTimingsMatchTheContract() {
        Timing t = Timing.defaults();
        assertEquals(15_000, t.pingMs);
        assertEquals(45_000, t.silenceMs);
        assertEquals(10_000, t.requestTimeoutMs);
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L), java.util.Arrays.stream(t.backoffMs).boxed().toList());
        assertEquals(15_000, t.slowRetryMs);
    }

    @Test void unansweredRequestFailsWithTimeout() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { /* never answers */ })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 300, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            long t0 = System.nanoTime();
            assertEquals(ErrorCode.TIMEOUT, failureOf(c.listUsers()));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 250 && ms < 2000, "timeout after " + ms + " ms");
            assertEquals(ConnectionState.CONNECTED, c.getConnectionState(), "a timeout does not drop the connection");
        }
    }

    @Test void lateReplyAfterTimeoutIsIgnored() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> {
            new Thread(() -> {
                try { Thread.sleep(500); FakeServer.send(s, FakeServer.frame(Protocol.USER_LIST_RESULT, f.get(1), "0")); } catch (Exception ignored) { }
            }).start();
        })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 200, 1000, new long[]{50}, 50));
            Recorder r = new Recorder(c);
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            assertEquals(ErrorCode.TIMEOUT, failureOf(c.listUsers()));
            Thread.sleep(600);
            assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
            assertEquals(0, r.count(e -> e instanceof ServerEvent.ServerError));
        }
    }

    @Test void connectionDroppedDuringARequestFailsItWithNetworkError() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> s.close())) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 5000, 1000, new long[]{50}, 50));
            Recorder r = new Recorder(c);
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            assertEquals(ErrorCode.NETWORK_ERROR, failureOf(c.listUsers()));
            r.await(state(ConnectionState.OFFLINE), 2000);                 // not logged in: just OFFLINE
        }
    }

    @Test void silentServerIsDetectedAsDeadAfterTheSilenceLimit() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { /* accepts, never says anything */ })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 400, 5000, 1000, new long[]{50}, 50));
            Recorder r = new Recorder(c);
            c.connect("127.0.0.1", fs.port());
            r.await(state(ConnectionState.CONNECTED), 3000);
            ServerEvent e = r.await(state(ConnectionState.OFFLINE), 3000);
            assertEquals("Connection lost", ((ServerEvent.ConnectionStateChanged) e).detail());
        }
    }

    @Test void malformedFramesAndUnknownFramesFromTheServerAreIgnored() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> {
            FakeServer.send(s, "garbage\\");                               // dangling escape
            FakeServer.send(s, "");                                        // empty line
            FakeServer.send(s, FakeServer.frame("WHAT_IS_THIS", f.get(1), "2"));   // even with the SAME request id it is not a reply
            FakeServer.send(s, FakeServer.frame(Protocol.USER_STATUS, "only-one-field"));
            FakeServer.send(s, FakeServer.frame(Protocol.OK, f.get(1)));
        })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 3000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            get(c.createRoom("general"));
            assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
        }
    }

    @Test void unsolicitedErrorBecomesAServerErrorEvent() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { }, s -> {
            try { FakeServer.send(s, FakeServer.frame(Protocol.ERROR, "0", "BAD_REQUEST")); } catch (IOException ignored) { }
        })) {
            SocketChatClient c = newClient(Runnable::run, fast());
            Recorder r = new Recorder(c);
            c.connect("127.0.0.1", fs.port());
            assertEquals(new ServerEvent.ServerError(ErrorCode.BAD_REQUEST), r.await(e -> e instanceof ServerEvent.ServerError, 3000));
        }
    }

    @Test void replyOfTheWrongKindFailsTheRequestInsteadOfHangingOrCrashing() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> FakeServer.send(s, FakeServer.frame(Protocol.OK, f.get(1))))) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 3000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            assertEquals(ErrorCode.BAD_REQUEST, failureOf(c.listUsers()));      // OK where USER_LIST_RESULT was expected
        }
    }

    // ================================================================== golden client frames
    @Test void clientFramesMatchTheGoldenFramesByteForByte() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 5000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            List<String> golden = GoldenFrames.clientFrames();         // LOGIN 1, MSG 7, SEARCH 9
            c.login("demo", "password1".toCharArray());                // request id 1
            for (int i = 2; i <= 6; i++) c.listUsers();                // ids 2..6
            c.sendRoomMessage("general", "line one\nline two");        // id 7
            c.listUsers();                                             // id 8
            c.search(new SearchQuery("build", "", 0, 0, false, null, ""));   // id 9
            List<String> sent = new ArrayList<>();
            for (int i = 0; i < 9; i++) sent.add(fs.nextLine(2000));
            assertEquals(golden.get(0), sent.get(0));
            assertEquals(golden.get(1), sent.get(6));
            assertEquals(golden.get(2), sent.get(8));
        }
    }

    @Test void otherRequestsUseTheDocumentedFieldOrder() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 5000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            c.register("alice", PW);
            c.listRooms(true);
            c.listRooms(false);
            c.createRoom("general");
            c.joinRoom("general");
            c.leaveRoom("general");
            c.listRoomMembers("general");
            c.listUsers();
            c.loadHistory(TargetType.ROOM, "general", 0, 50);
            c.loadHistoryAround(TargetType.PRIVATE, "bob", 77);
            c.sendPrivateMessage("bob", "hi");
            c.markRead(TargetType.PRIVATE, "bob", 5);
            c.search(new SearchQuery("kw", "bob", 10, 20, true, TargetType.ROOM, "general"));
            c.sendTyping(TargetType.ROOM, "general");
            String[] expected = {
                    "REGISTER\t1\talice\tpassword1", "ROOM_LIST\t2\tMINE", "ROOM_LIST\t3\tALL", "ROOM_CREATE\t4\tgeneral",
                    "ROOM_JOIN\t5\tgeneral", "ROOM_LEAVE\t6\tgeneral", "ROOM_MEMBERS\t7\tgeneral", "USER_LIST\t8",
                    "HISTORY\t9\tROOM\tgeneral\t0\t50", "HISTORY_AROUND\t10\tPRIVATE\tbob\t77", "PM\t11\tbob\thi",
                    "MARK_READ\t12\tPRIVATE\tbob\t5", "SEARCH\t13\tkw\tbob\t10\t20\tCURRENT\tROOM\tgeneral",
                    "TYPING\t0\tROOM\tgeneral"};
            for (String e : expected) assertEquals(e, fs.nextLine(2000));
        }
    }

    // ================================================================== reconnect
    @Test void connectionDropTriggersReconnectAndSilentRelogin() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = connected(port, r);
        char[] mine = "password1".toCharArray();
        get(c.register("alice", mine));
        get(c.login("alice", mine));
        java.util.Arrays.fill(mine, 'x');                              // the caller wipes its copy: the client must have its own

        server.stop();
        ServerEvent.ConnectionStateChanged rc = (ServerEvent.ConnectionStateChanged) r[0].await(state(ConnectionState.RECONNECTING), 3000);
        assertEquals(1, rc.retryInSeconds());
        startServer(svc, port);                                        // same port, same users
        r[0].await(e -> e instanceof ServerEvent.SessionRestored, 5000);
        assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
        assertEquals(List.of(), get(c.listUsers()));                   // logged in again without typing the password
        assertEquals(1, readerThreads(), "exactly one reader thread after the reconnect");
        assertEquals(1, r[0].count(e -> e instanceof ServerEvent.SessionRestored));
    }

    @Test void repeatedDropsNeverLeaveMoreThanOneReader() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = loggedIn(port, "alice", r);
        for (int i = 1; i <= 3; i++) {
            server.stop();
            startServer(svc, port);
            int expected = i;
            assertTrue(waitFor(() -> r[0].count(e -> e instanceof ServerEvent.SessionRestored) == expected, 5000), "restore #" + i);
            assertEquals(1, readerThreads());
        }
        assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
    }

    @Test void pendingRequestsFailWithNetworkErrorWhenTheConnectionDrops() throws Exception {
        try (FakeServer fs = new FakeServer((s, f) -> { /* ignore */ })) {
            SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 10_000, 1000, new long[]{50}, 50));
            c.connect("127.0.0.1", fs.port());
            assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.CONNECTED, 3000));
            CompletableFuture<List<UserSummary>> f = c.listUsers();
            Thread.sleep(100);
            fs.closeClients();
            assertEquals(ErrorCode.NETWORK_ERROR, failureOf(f));
        }
    }

    @Test void rejectedCredentialsOnReconnectEndTheSession() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = loggedIn(port, "alice", r);
        server.stop();
        r[0].await(state(ConnectionState.RECONNECTING), 3000);
        startServer(new InMemoryChatService(), port);                  // a server that has never heard of alice
        ServerEvent.SessionEnded ended = (ServerEvent.SessionEnded) r[0].await(e -> e instanceof ServerEvent.SessionEnded, 5000);
        assertFalse(ended.reason().isBlank());
        assertTrue(waitFor(() -> c.getConnectionState() == ConnectionState.OFFLINE, 2000));
        assertTrue(r[0].none(e -> e instanceof ServerEvent.SessionRestored, 300));
        Thread.sleep(300);
        assertEquals(ConnectionState.OFFLINE, c.getConnectionState(), "no further reconnect attempts after the session ended");
        assertTrue(waitFor(() -> readerThreads() == 0, 1000));
    }

    @Test void afterFiveFailedAttemptsStateIsOfflineButRetryingContinues() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = loggedIn(port, "alice", r);
        server.stop();
        r[0].await(state(ConnectionState.RECONNECTING), 3000);
        ServerEvent off = r[0].await(state(ConnectionState.OFFLINE), 5000);
        assertEquals("Can't reach server", ((ServerEvent.ConnectionStateChanged) off).detail());
        startServer(svc, port);                                        // it keeps trying: the server coming back is noticed
        r[0].await(e -> e instanceof ServerEvent.SessionRestored, 5000);
        assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
    }

    @Test void backoffWaitsGrowAs1_2_4_8ThenFifteenSeconds() throws Exception {
        // Observe the retryInSeconds values the client reports while the server stays down (scaled-down timings).
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 2000, 500, new long[]{1000, 2000, 4000, 8000}, 15_000));
        Recorder r = new Recorder(c);
        c.connect("127.0.0.1", port);
        r.await(state(ConnectionState.CONNECTED), 3000);
        get(c.register("alice", PW));
        get(c.login("alice", PW));
        server.stop();
        ServerEvent.ConnectionStateChanged first = (ServerEvent.ConnectionStateChanged) r.await(state(ConnectionState.RECONNECTING), 3000);
        assertEquals(1, first.retryInSeconds());
        ServerEvent.ConnectionStateChanged second = (ServerEvent.ConnectionStateChanged) r.await(
                e -> e instanceof ServerEvent.ConnectionStateChanged s && s.state() == ConnectionState.RECONNECTING && s.retryInSeconds() == 2, 4000);
        assertEquals(ConnectionState.RECONNECTING, second.state());
    }

    @Test void retryNowSkipsTheBackoffWait() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        SocketChatClient c = newClient(Runnable::run, new Timing(60_000, 60_000, 2000, 1000, new long[]{30_000}, 30_000));
        Recorder r = new Recorder(c);
        c.connect("127.0.0.1", port);
        r.await(state(ConnectionState.CONNECTED), 3000);
        get(c.register("alice", PW));
        get(c.login("alice", PW));
        server.stop();
        r.await(state(ConnectionState.RECONNECTING), 3000);
        startServer(svc, port);
        Thread.sleep(300);
        assertEquals(ConnectionState.RECONNECTING, c.getConnectionState(), "still waiting out the 30 s backoff");
        c.retryNow();
        r.await(e -> e instanceof ServerEvent.SessionRestored, 3000);
        assertEquals(ConnectionState.CONNECTED, c.getConnectionState());
    }

    // ================================================================== logout, session replaced, shutdown
    @Test void logoutSendsLogoutClosesAndNeverReconnects() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] ra = new Recorder[1], rb = new Recorder[1];
        SocketChatClient a = loggedIn(port, "alice", ra);
        SocketChatClient b = loggedIn(port, "bobby", rb);
        ra[0].await(e -> e instanceof ServerEvent.UserStatus u && u.username().equals("bobby"), 2000);
        get(b.logout());
        assertEquals(ConnectionState.OFFLINE, b.getConnectionState());
        ra[0].await(e -> e instanceof ServerEvent.UserStatus u && u.username().equals("bobby") && !u.online(), 2000);
        assertTrue(rb[0].none(state(ConnectionState.RECONNECTING), 400));
        assertEquals(ErrorCode.NOT_CONNECTED, failureOf(b.listUsers()));
        // signing in again as someone else works after reconnecting the socket
        b.connect("127.0.0.1", port);
        assertTrue(waitFor(() -> b.getConnectionState() == ConnectionState.CONNECTED, 3000));
        get(b.login("bobby", PW));
    }

    @Test void logoutWhileOfflineJustCompletes() throws Exception {
        SocketChatClient c = newClient();
        get(c.logout());
        assertEquals(ConnectionState.OFFLINE, c.getConnectionState());
    }

    @Test void logoutWipesCredentialsSoAServerRestartDoesNotLogInAgain() throws Exception {
        InMemoryChatService svc = new InMemoryChatService();
        int port = startServer(svc, 0);
        Recorder[] r = new Recorder[1];
        SocketChatClient c = loggedIn(port, "alice", r);
        get(c.logout());
        server.stop();
        startServer(svc, port);
        Thread.sleep(500);
        assertEquals(ConnectionState.OFFLINE, c.getConnectionState());
        assertEquals(0, r[0].count(e -> e instanceof ServerEvent.SessionRestored));
    }

    @Test void loginFromAnotherClientEndsTheFirstSessionWithoutReconnecting() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        Recorder[] r1 = new Recorder[1];
        SocketChatClient first = loggedIn(port, "alice", r1);
        SocketChatClient second = connected(port, null);
        get(second.login("alice", PW));
        ServerEvent.SessionEnded ended = (ServerEvent.SessionEnded) r1[0].await(e -> e instanceof ServerEvent.SessionEnded, 3000);
        assertEquals("Your account was signed in somewhere else.", ended.reason());
        assertTrue(waitFor(() -> first.getConnectionState() == ConnectionState.OFFLINE, 2000));
        Thread.sleep(400);
        assertEquals(ConnectionState.OFFLINE, first.getConnectionState());
        assertEquals(0, r1[0].count(state(ConnectionState.RECONNECTING)), "must not fight the new session by reconnecting");
        assertEquals(List.of(), get(second.listUsers()), "the new session stays alive");
    }

    @Test void disconnectIsCleanAndShutdownStopsAllBackgroundThreads() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        SocketChatClient c = loggedIn(port, "alice", null);
        CompletableFuture<List<UserSummary>> inflight = c.listUsers();
        c.disconnect();
        assertEquals(ConnectionState.OFFLINE, c.getConnectionState());
        inflight.handle((v, e) -> null).get(2, TimeUnit.SECONDS);       // either answered or failed, never hangs
        c.shutdown();
        assertTrue(waitFor(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("chatsphere-client-")), 3000), "client threads still alive");
    }

    @Test void manyClientsAtOnceAllReceiveEveryRoomMessage() throws Exception {
        int port = startServer(new InMemoryChatService(), 0);
        int n = 12;
        List<SocketChatClient> cs = new ArrayList<>();
        List<AtomicInteger> got = new ArrayList<>();
        SocketChatClient first = loggedIn(port, "user0", null);
        get(first.createRoom("general"));
        for (int i = 0; i < n; i++) {
            SocketChatClient c = i == 0 ? first : loggedIn(port, "user" + i, null);
            if (i > 0) get(c.joinRoom("general"));
            AtomicInteger cnt = new AtomicInteger();
            c.addEventListener(e -> { if (e instanceof ServerEvent.RoomMessage) cnt.incrementAndGet(); });
            cs.add(c);
            got.add(cnt);
        }
        List<CompletableFuture<SentAck>> sends = new ArrayList<>();
        for (int i = 0; i < n; i++) sends.add(cs.get(i).sendRoomMessage("general", "hello from " + i));
        for (CompletableFuture<SentAck> f : sends) get(f);
        for (int i = 0; i < n; i++) {
            AtomicInteger cnt = got.get(i);
            assertTrue(waitFor(() -> cnt.get() == n - 1, 4000), "client " + i + " got " + cnt.get());
        }
    }
}
