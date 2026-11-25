package com.chatsphere.client.net;

import com.chatsphere.client.net.ServerEvent.*;
import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.server.service.InMemoryChatService;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dev client with scripted data and behavior. Lets Person 3 and Person 4 build the whole UI with no server.
 *
 * Accounts (password "password1"): demo (populated workspace), alice, bob, carol, dave. Registering any NEW name gives
 * the empty first-run state. Magic: host "unreachable" -> OFFLINE; message text containing "#fail" -> send fails;
 * simulateDrop() -> RECONNECTING for 3 s then SessionRestored. Bots chat in your rooms every ~9 s, reply to
 * private messages, show typing, and move your private-message ticks SENT -> DELIVERED -> READ.
 * Constructor takes the UI executor: pass Platform::runLater in the app, Runnable::run in tests.
 */
public class FakeChatClient implements ChatClient {
    private static final long LATENCY_MS = 150;
    private static final List<String> BOTS = List.of("alice", "bob", "carol", "dave");
    private static final List<String> LINES = List.of(
            "Has anyone seen the latest build?", "Sounds good to me.", "I will push my changes in ten minutes.",
            "Lunch at one?", "Can someone review my pull request?\nIt is a small one.",
            "Found the bug, it was a null check.", "Thanks, that helped.", "Meeting moved to 3 pm.");

    private final Executor ui;
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "fake-chat-client"); t.setDaemon(true); return t; });
    private final InMemoryChatService service = new InMemoryChatService();
    private final Set<String> onlineBots = ConcurrentHashMap.newKeySet();
    private final List<Consumer<ServerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Random rnd = new Random();
    private volatile ConnectionState state = ConnectionState.OFFLINE;
    private volatile String me;
    private volatile ScheduledFuture<?> botLoop;

    private final boolean sync;

    public FakeChatClient(Executor uiExecutor) {
        this.ui = uiExecutor;
        this.sync = isSyncExecutor(uiExecutor);
        seed();
    }

    private static boolean isSyncExecutor(Executor ex) {
        if (ex == null) return false;
        boolean[] called = new boolean[1];
        try {
            ex.execute(() -> called[0] = true);
        } catch (Throwable t) {
            return false;
        }
        return called[0];
    }

    // ---------------------------------------------------------------- seed data
    private void seed() {
        for (String u : List.of("alice", "bob", "carol", "dave", "demo")) service.register(u, "password1");
        service.createRoom("alice", "general");
        for (String u : List.of("bob", "carol", "demo")) service.joinRoom(u, "general");
        service.createRoom("alice", "random");
        service.joinRoom("bob", "random");
        service.joinRoom("demo", "random");
        service.createRoom("carol", "design");
        service.saveRoomMessage("alice", "general", "Welcome to ChatSphere. Say hi.");
        service.saveRoomMessage("bob", "general", "Did anyone look at the build?\nIt failed on CI.");
        service.saveRoomMessage("carol", "general", "On it.");
        service.saveRoomMessage("bob", "random", "Anyone up for coffee?");
        MessageRecord a = service.savePrivateMessage("alice", "demo", "Hey, are you free for a quick call?");
        service.savePrivateMessage("bob", "demo", "Pushed the fix.");
        service.savePrivateMessage("demo", "alice", "Give me five minutes.");
        service.markDelivered(List.of(a.id()));
        onlineBots.add("alice");
        onlineBots.add("bob");
    }

    // ---------------------------------------------------------------- plumbing
    private void emit(ServerEvent e) {
        ui.execute(() -> { for (Consumer<ServerEvent> l : listeners) l.accept(e); });
    }
    private void setState(ConnectionState s, int retry, String detail) {
        state = s;
        emit(new ConnectionStateChanged(s, retry, detail));
    }
    private void later(long ms, Runnable r) {
        sched.schedule(() -> { try { r.run(); } catch (RuntimeException ignored) { } }, ms, TimeUnit.MILLISECONDS);
    }
    private <T> CompletableFuture<T> call(boolean needsLogin, Supplier<T> action) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                if (state != ConnectionState.CONNECTED) throw new ChatException(ErrorCode.NOT_CONNECTED);
                if (needsLogin && me == null) throw new ChatException(ErrorCode.NOT_LOGGED_IN);
                T v = action.get();
                ui.execute(() -> f.complete(v));
            } catch (ChatException ex) {
                ui.execute(() -> f.completeExceptionally(ex));
            } catch (RuntimeException ex) {
                ui.execute(() -> f.completeExceptionally(new ChatException(ErrorCode.SERVER_ERROR, ex.toString())));
            }
        };
        if (sync) {
            task.run();
        } else {
            sched.schedule(task, LATENCY_MS, TimeUnit.MILLISECONDS);
        }
        return f;
    }

    // ---------------------------------------------------------------- connection
    @Override public void connect(String host, int port) {
        if (sync) {
            if ("unreachable".equals(host)) setState(ConnectionState.OFFLINE, 0, "Can't reach server");
            else setState(ConnectionState.CONNECTED, 0, "");
            return;
        }
        setState(ConnectionState.CONNECTING, 0, "");
        later(400, () -> {
            if ("unreachable".equals(host)) setState(ConnectionState.OFFLINE, 0, "Can't reach server");
            else setState(ConnectionState.CONNECTED, 0, "");
        });
    }
    @Override public void disconnect() { stopBots(); setState(ConnectionState.OFFLINE, 0, ""); }
    @Override public void retryNow() { connect("localhost", 5050); }
    @Override public ConnectionState getConnectionState() { return state; }
    @Override public Runnable addEventListener(Consumer<ServerEvent> l) { listeners.add(l); return () -> listeners.remove(l); }

    /** Dev helper: pretend the network dropped for 3 seconds. */
    public void simulateDrop() {
        setState(ConnectionState.RECONNECTING, 3, "");
        later(3000, () -> {
            setState(ConnectionState.CONNECTED, 0, "");
            if (me != null) emit(new SessionRestored());
        });
    }

    // ---------------------------------------------------------------- session
    @Override public CompletableFuture<Void> register(String username, char[] password) {
        String p = new String(password);
        return call(false, () -> { service.register(username, p); return null; });
    }
    @Override public CompletableFuture<Void> login(String username, char[] password) {
        String p = new String(password);
        return call(false, () -> {
            service.verifyLogin(username, p);
            me = username;
            if (!sync) startBots();
            return null;
        });
    }
    @Override public CompletableFuture<Void> logout() {
        return call(true, () -> { stopBots(); me = null; return null; });
    }

    // ---------------------------------------------------------------- rooms & users
    @Override public CompletableFuture<List<RoomSummary>> listRooms(boolean onlyMine) {
        return call(true, () -> service.listRooms(me, onlyMine));
    }
    @Override public CompletableFuture<Void> createRoom(String room) {
        return call(true, () -> { service.createRoom(me, room); return null; });
    }
    @Override public CompletableFuture<Void> joinRoom(String room) {
        return call(true, () -> {
            service.joinRoom(me, room);
            emit(new RoomMembersChanged(room, me, true));
            return null;
        });
    }
    @Override public CompletableFuture<Void> leaveRoom(String room) {
        return call(true, () -> {
            service.leaveRoom(me, room);
            emit(new RoomMembersChanged(room, me, false));
            return null;
        });
    }
    @Override public CompletableFuture<List<MemberInfo>> listRoomMembers(String room) {
        return call(true, () -> {
            List<MemberInfo> out = new ArrayList<>();
            for (String u : service.roomMembers(me, room)) out.add(new MemberInfo(u, u.equals(me) || onlineBots.contains(u)));
            return out;
        });
    }
    @Override public CompletableFuture<List<UserSummary>> listUsers() {
        return call(true, () -> {
            List<UserSummary> out = new ArrayList<>();
            for (UserSummary u : service.listUsers(me))
                out.add(new UserSummary(u.username(), onlineBots.contains(u.username()), u.unread(), u.lastText(), u.lastTimestamp()));
            return out;
        });
    }

    // ---------------------------------------------------------------- messaging
    @Override public CompletableFuture<HistoryPage> loadHistory(TargetType type, String target, long beforeId, int limit) {
        return call(true, () -> service.history(me, type, target, beforeId, limit));
    }
    @Override public CompletableFuture<HistoryPage> loadHistoryAround(TargetType type, String target, long messageId) {
        return call(true, () -> service.historyAround(me, type, target, messageId));
    }
    @Override public CompletableFuture<SentAck> sendRoomMessage(String room, String text) {
        return call(true, () -> {
            if (text.contains("#fail")) throw new ChatException(ErrorCode.NETWORK_ERROR);
            MessageRecord r = service.saveRoomMessage(me, room, text);
            if (rnd.nextInt(3) == 0) later(2500, () -> botSaysInRoom(room));
            return new SentAck(r.id(), r.timestamp());
        });
    }
    @Override public CompletableFuture<SentAck> sendPrivateMessage(String user, String text) {
        return call(true, () -> {
            if (text.contains("#fail")) throw new ChatException(ErrorCode.NETWORK_ERROR);
            MessageRecord r = service.savePrivateMessage(me, user, text);
            if (onlineBots.contains(user)) {
                later(700, () -> {
                    service.markDelivered(List.of(r.id()));
                    emit(new StatusUpdate(DeliveryStatus.DELIVERED, user, List.of(r.id())));
                });
                later(2500, () -> {
                    List<Long> ids = service.markPrivateRead(user, me, r.id());
                    if (!ids.isEmpty()) emit(new StatusUpdate(DeliveryStatus.READ, user, ids));
                    botReply(user);
                });
            }
            return new SentAck(r.id(), r.timestamp());
        });
    }
    @Override public void sendTyping(TargetType type, String target) { /* nothing to relay in the fake */ }
    @Override public CompletableFuture<Void> markRead(TargetType type, String target, long upTo) {
        return call(true, () -> {
            if (type == TargetType.PRIVATE) service.markPrivateRead(me, target, upTo);
            else service.markRoomRead(me, target, upTo);
            return null;
        });
    }
    @Override public CompletableFuture<List<SearchHit>> search(SearchQuery q) {
        return call(true, () -> service.search(me, q));
    }

    // ---------------------------------------------------------------- bots
    private void startBots() {
        stopBots();
        botLoop = sched.scheduleWithFixedDelay(() -> {
            try { botTick(); } catch (RuntimeException ignored) { }
        }, 6000, 9000, TimeUnit.MILLISECONDS);
        // carol goes online/offline every 20 s
        sched.scheduleWithFixedDelay(() -> {
            try { toggleCarol(); } catch (RuntimeException ignored) { }
        }, 20000, 20000, TimeUnit.MILLISECONDS);
    }
    private void stopBots() {
        ScheduledFuture<?> f = botLoop;
        if (f != null) f.cancel(false);
        botLoop = null;
    }
    private void toggleCarol() {
        String who = me;
        if (who == null) return;
        if (onlineBots.remove("carol")) {
            emit(new UserStatus("carol", false));
        } else {
            onlineBots.add("carol");
            emit(new UserStatus("carol", true));
            Map<String, List<Long>> delivered = service.markAllDeliveredFor("carol");
            for (Map.Entry<String, List<Long>> e : delivered.entrySet())
                if (e.getKey().equals(who)) emit(new StatusUpdate(DeliveryStatus.DELIVERED, "carol", e.getValue()));
        }
    }
    private void botTick() {
        String who = me;
        if (who == null) return;
        List<RoomSummary> mine = service.listRooms(who, true);
        if (mine.isEmpty()) return;
        botSaysInRoom(mine.get(rnd.nextInt(mine.size())).name());
    }
    private void botSaysInRoom(String room) {
        String who = me;
        if (who == null) return;
        List<String> candidates = new ArrayList<>();
        for (String m : service.roomMembers(who, room)) if (!m.equals(who) && onlineBots.contains(m)) candidates.add(m);
        if (candidates.isEmpty()) return;
        String bot = candidates.get(rnd.nextInt(candidates.size()));
        emit(new Typing(TargetType.ROOM, room, bot));
        later(1500, () -> {
            MessageRecord r = service.saveRoomMessage(bot, room, LINES.get(rnd.nextInt(LINES.size())));
            emit(new RoomMessage(room, r));
        });
    }
    private void botReply(String bot) {
        String who = me;
        if (who == null) return;
        emit(new Typing(TargetType.PRIVATE, bot, bot));
        later(1500, () -> {
            MessageRecord r = service.savePrivateMessage(bot, who, LINES.get(rnd.nextInt(LINES.size())));
            service.markDelivered(List.of(r.id()));
            emit(new PrivateMessage(bot, new MessageRecord(r.id(), r.sender(), r.timestamp(), DeliveryStatus.DELIVERED, r.text())));
        });
    }
}
