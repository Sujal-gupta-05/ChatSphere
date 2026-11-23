package com.chatsphere.server.net;

import com.chatsphere.server.service.InMemoryChatService;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry();

    /** A handler that was never run: it only has an outbound queue we can inspect. */
    private ClientHandler handler() { return new ClientHandler(new Socket(), new InMemoryChatService(), registry); }

    @Test void firstRegisterReturnsNullAndMarksOnline() {
        ClientHandler a = handler();
        assertNull(registry.register("alice", a));
        assertTrue(registry.isOnline("alice"));
        assertFalse(registry.isOnline("bob"));
        assertEquals(1, registry.onlineCount());
    }

    @Test void secondRegisterReturnsTheReplacedHandler() {
        ClientHandler a = handler(), b = handler();
        registry.register("alice", a);
        assertSame(a, registry.register("alice", b));
        assertEquals(1, registry.onlineCount());
    }

    @Test void unregisterOnlyRemovesTheCurrentHandler() {
        ClientHandler oldH = handler(), newH = handler();
        registry.register("alice", oldH);
        registry.register("alice", newH);
        assertFalse(registry.unregister("alice", oldH), "old connection must not take the new session offline");
        assertTrue(registry.isOnline("alice"));
        assertTrue(registry.unregister("alice", newH));
        assertFalse(registry.isOnline("alice"));
        assertFalse(registry.unregister("alice", newH), "second unregister is a no-op");
    }

    @Test void sendToQueuesForThatUserOnly() {
        ClientHandler a = handler(), b = handler();
        registry.register("alice", a);
        registry.register("bob", b);
        assertTrue(registry.sendTo("alice", "F1"));
        assertEquals("F1", a.pollOutbound());
        assertNull(b.pollOutbound());
    }

    @Test void sendToOfflineUserReturnsFalse() {
        assertFalse(registry.sendTo("ghost", "F"));
    }

    @Test void broadcastSkipsTheExcludedUser() {
        ClientHandler a = handler(), b = handler(), c = handler();
        registry.register("alice", a);
        registry.register("bob", b);
        registry.register("carol", c);
        registry.broadcast("X", "bob");
        assertEquals("X", a.pollOutbound());
        assertNull(b.pollOutbound());
        assertEquals("X", c.pollOutbound());
    }

    @Test void broadcastWithNullExceptReachesEveryone() {
        ClientHandler a = handler(), b = handler();
        registry.register("alice", a);
        registry.register("bob", b);
        registry.broadcast("Y", null);
        assertEquals("Y", a.pollOutbound());
        assertEquals("Y", b.pollOutbound());
    }

    @Test void broadcastOnEmptyRegistryIsHarmless() { registry.broadcast("Z", null); }

    @Test void simultaneousLoginsOfTheSameUserLeaveExactlyOneWinnerAndReplaceEachOtherOnce() throws Exception {
        int n = 50;
        List<ClientHandler> hs = new ArrayList<>();
        for (int i = 0; i < n; i++) hs.add(handler());
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        List<ClientHandler> replaced = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nulls = new AtomicInteger();
        for (ClientHandler h : hs) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    ClientHandler old = registry.register("alice", h);
                    if (old == null) nulls.incrementAndGet(); else replaced.add(old);
                } catch (InterruptedException ignored) {
                } finally { done.countDown(); }
            });
        }
        go.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, nulls.get(), "exactly one registration saw no previous handler");
        assertEquals(n - 1, replaced.size());
        assertEquals(n - 1, new java.util.HashSet<>(replaced).size(), "every handler is replaced at most once");
        assertEquals(1, registry.onlineCount());
    }

    @Test void concurrentRegisterAndUnregisterOfDifferentUsersKeepsCountsConsistent() throws Exception {
        int n = 200;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            String name = "user" + i;
            Thread.ofVirtual().start(() -> {
                ClientHandler h = handler();
                registry.register(name, h);
                if (name.hashCode() % 2 == 0) registry.unregister(name, h);
                done.countDown();
            });
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        int expected = 0;
        for (int i = 0; i < n; i++) if ("user".concat(String.valueOf(i)).hashCode() % 2 != 0) expected++;
        assertEquals(expected, registry.onlineCount());
    }
}
