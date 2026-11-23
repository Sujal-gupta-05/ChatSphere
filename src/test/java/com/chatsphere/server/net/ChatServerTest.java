package com.chatsphere.server.net;

import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.server.service.InMemoryChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Milestone 1: server start/stop, REGISTER, LOGIN, LOGOUT, PING, session replacement, basic error handling. */
class ChatServerTest {
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

    private RawClient connect() throws IOException {
        RawClient c = new RawClient(port);
        clients.add(c);
        return c;
    }

    // ------------------------------------------------------------------ lifecycle
    @Test void startReturnsTheBoundPortAndAcceptsConnections() throws Exception {
        assertTrue(port > 0);
        try (Socket s = new Socket("127.0.0.1", port)) { assertTrue(s.isConnected()); }
    }

    @Test void startingTwiceIsRejected() {
        assertThrows(IllegalStateException.class, () -> server.start());
    }

    @Test void stopIsIdempotentAndClosesTheListeningPort() throws Exception {
        server.stop();
        server.stop();
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test void stopDisconnectsConnectedClients() throws Exception {
        RawClient a = connect();
        a.registerAndLogin("alice", "password1");
        server.stop();
        assertTrue(a.closedByServer());
    }

    @Test void serverCanBeStartedAgainOnTheSamePortAfterStop() throws Exception {
        server.stop();
        ChatServer again = new ChatServer(port, new InMemoryChatService());
        assertEquals(port, again.start());
        try (RawClient c = new RawClient(port)) {
            c.send(Protocol.PING, "1");
            assertEquals("PONG|1", c.nextPipe());
        } finally { again.stop(); }
    }

    // ------------------------------------------------------------------ register / login
    @Test void registerThenLoginWorks() throws Exception {
        RawClient a = connect();
        a.send(Protocol.REGISTER, "1", "alice", "password1");
        assertEquals("OK|1", a.nextPipe());
        a.send(Protocol.LOGIN, "2", "alice", "password1");
        assertEquals("LOGIN_OK|2|alice", a.nextPipe());
    }

    @Test void registerDoesNotLogIn() throws Exception {
        RawClient a = connect();
        a.send(Protocol.REGISTER, "1", "alice", "password1");
        assertEquals("OK|1", a.nextPipe());
        a.send(Protocol.USER_LIST, "2");
        assertEquals("ERROR|2|NOT_LOGGED_IN", a.nextPipe());
    }

    @Test void registerErrorsUseTheServiceCodes() throws Exception {
        RawClient a = connect();
        a.send(Protocol.REGISTER, "1", "ab", "password1");
        assertEquals("ERROR|1|INVALID_USERNAME", a.nextPipe());
        a.send(Protocol.REGISTER, "2", "alice", "short");
        assertEquals("ERROR|2|INVALID_PASSWORD", a.nextPipe());
        a.send(Protocol.REGISTER, "3", "alice", "password1");
        assertEquals("OK|3", a.nextPipe());
        a.send(Protocol.REGISTER, "4", "alice", "password1");
        assertEquals("ERROR|4|USERNAME_TAKEN", a.nextPipe());
    }

    @Test void wrongPasswordAndUnknownUserBothGiveBadCredentials() throws Exception {
        RawClient a = connect();
        a.send(Protocol.REGISTER, "1", "alice", "password1");
        a.nextPipe();
        a.send(Protocol.LOGIN, "2", "alice", "wrongpass1");
        assertEquals("ERROR|2|BAD_CREDENTIALS", a.nextPipe());
        a.send(Protocol.LOGIN, "3", "nobody", "password1");
        assertEquals("ERROR|3|BAD_CREDENTIALS", a.nextPipe());
        a.send(Protocol.LOGIN, "4", "alice", "password1");           // connection still usable after failures
        assertEquals("LOGIN_OK|4|alice", a.nextPipe());
    }

    @Test void loginTwiceOnTheSameConnectionIsBadRequest() throws Exception {
        RawClient a = connect();
        a.registerAndLogin("alice", "password1");
        a.send(Protocol.LOGIN, "3", "alice", "password1");
        assertEquals("ERROR|3|BAD_REQUEST", a.nextPipe());
    }

    @Test void registerWhileLoggedInIsBadRequest() throws Exception {
        RawClient a = connect();
        a.registerAndLogin("alice", "password1");
        a.send(Protocol.REGISTER, "3", "bobby", "password1");
        assertEquals("ERROR|3|BAD_REQUEST", a.nextPipe());
    }

    @Test void passwordWithSpecialCharactersSurvivesTheWire() throws Exception {
        RawClient a = connect();
        String pw = "pa\tss\\n word\n!";                               // tab, backslash+n, newline
        a.send(Protocol.REGISTER, "1", "alice", pw);
        assertEquals("OK|1", a.nextPipe());
        a.send(Protocol.LOGIN, "2", "alice", pw);
        assertEquals("LOGIN_OK|2|alice", a.nextPipe());
    }

    // ------------------------------------------------------------------ ping, not logged in, unknown, malformed
    @Test void pingWorksBeforeLogin() throws Exception {
        RawClient a = connect();
        a.send(Protocol.PING, "77");
        assertEquals("PONG|77", a.nextPipe());
    }

    @Test void commandsBeforeLoginAreRejectedWithNotLoggedIn() throws Exception {
        RawClient a = connect();
        a.send(Protocol.ROOM_LIST, "1", "MINE");
        assertEquals("ERROR|1|NOT_LOGGED_IN", a.nextPipe());
        a.send(Protocol.MSG, "2", "general", "hi");
        assertEquals("ERROR|2|NOT_LOGGED_IN", a.nextPipe());
        a.send(Protocol.LOGOUT, "3");
        assertEquals("ERROR|3|NOT_LOGGED_IN", a.nextPipe());
    }

    @Test void typingBeforeLoginIsSilentlyIgnored() throws Exception {
        RawClient a = connect();
        a.send(Protocol.TYPING, "0", "ROOM", "general");
        assertTrue(a.silentFor(300));
        a.send(Protocol.PING, "1");
        assertEquals("PONG|1", a.nextPipe());
    }

    @Test void unknownCommandGetsUnknownCommandAndConnectionStaysOpen() throws Exception {
        RawClient a = connect();
        a.send("FLY_TO_MOON", "5");
        assertEquals("ERROR|5|UNKNOWN_COMMAND", a.nextPipe());
        a.send(Protocol.PING, "6");
        assertEquals("PONG|6", a.nextPipe());
    }

    @Test void wrongArityIsBadRequestWithTheReqIdAndConnectionStaysOpen() throws Exception {
        RawClient a = connect();
        a.send(Protocol.LOGIN, "9", "alice");                        // password missing
        assertEquals("ERROR|9|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.PING, "10", "extra");
        assertEquals("ERROR|10|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.PING, "11");
        assertEquals("PONG|11", a.nextPipe());
    }

    @Test void commandWithoutReqIdGetsReqIdZero() throws Exception {
        RawClient a = connect();
        a.send(Protocol.PING);
        assertEquals("ERROR|0|BAD_REQUEST", a.nextPipe());
    }

    @Test void emptyLineAndBadEscapeAreBadRequestAndConnectionStaysOpen() throws Exception {
        RawClient a = connect();
        a.sendRaw("");
        assertEquals("ERROR|0|BAD_REQUEST", a.nextPipe());
        a.sendRaw("PING\t4\\q");                                     // unknown escape sequence in a field
        assertEquals("ERROR|0|BAD_REQUEST", a.nextPipe());
        a.sendRaw("\tPING");                                         // empty command name
        assertEquals("ERROR|0|BAD_REQUEST", a.nextPipe());
        a.send(Protocol.PING, "5");
        assertEquals("PONG|5", a.nextPipe());
    }

    @Test void frameJustUnderTheLimitIsAcceptedButOverIsRejectedAndClosed() throws Exception {
        RawClient ok = connect();
        // exactly MAX_LINE_BYTES bytes (without the newline): "PING\t1\t" + padding -> wrong arity, but READ fine
        String exact = "PING\t1\t" + "x".repeat(Limits.MAX_LINE_BYTES - 7);
        assertEquals(Limits.MAX_LINE_BYTES, exact.length());
        ok.sendRaw(exact);
        assertEquals("ERROR|1|BAD_REQUEST", ok.nextPipe());          // processed as a normal (bad arity) frame
        ok.send(Protocol.PING, "2");
        assertEquals("PONG|2", ok.nextPipe());

        RawClient big = connect();
        big.sendRaw("PING\t1\t" + "x".repeat(70_000));
        assertEquals("ERROR|0|BAD_REQUEST", big.nextPipe());
        assertTrue(big.closedByServer());
    }

    @Test void utf8TextWorksInNamesAndPasswords() throws Exception {
        RawClient a = connect();
        a.send(Protocol.REGISTER, "1", "alice", "p\u00e4ssw\u00f6rd\uD83D\uDE00");
        assertEquals("OK|1", a.nextPipe());
        a.send(Protocol.LOGIN, "2", "alice", "p\u00e4ssw\u00f6rd\uD83D\uDE00");
        assertEquals("LOGIN_OK|2|alice", a.nextPipe());
    }

    // ------------------------------------------------------------------ presence and session replacement
    @Test void secondUserLoginIsAnnouncedToTheFirst() throws Exception {
        RawClient a = connect(), b = connect();
        a.registerAndLogin("alice", "password1");
        b.registerAndLogin("bobby", "password1");
        assertEquals("USER_STATUS|bobby|1", a.nextPipe());
    }

    @Test void loginDoesNotAnnounceToTheUserHimself() throws Exception {
        RawClient a = connect();
        a.registerAndLogin("alice", "password1");
        assertTrue(a.silentFor(300));
    }

    @Test void disconnectIsAnnouncedAsOffline() throws Exception {
        RawClient a = connect(), b = connect();
        a.registerAndLogin("alice", "password1");
        b.registerAndLogin("bobby", "password1");
        assertEquals("USER_STATUS|bobby|1", a.nextPipe());
        b.close();
        assertEquals("USER_STATUS|bobby|0", a.nextPipe());
    }

    @Test void logoutRepliesOkThenClosesAndAnnouncesOffline() throws Exception {
        RawClient a = connect(), b = connect();
        a.registerAndLogin("alice", "password1");
        b.registerAndLogin("bobby", "password1");
        assertEquals("USER_STATUS|bobby|1", a.nextPipe());
        b.send(Protocol.LOGOUT, "3");
        assertEquals("OK|3", b.nextPipe());
        assertTrue(b.closedByServer());
        assertEquals("USER_STATUS|bobby|0", a.nextPipe());
    }

    @Test void secondLoginOfTheSameUserReplacesTheFirstConnection() throws Exception {
        RawClient first = connect(), second = connect(), watcher = connect();
        first.registerAndLogin("alice", "password1");
        watcher.registerAndLogin("bobby", "password1");
        assertEquals("USER_STATUS|bobby|1", first.nextPipe());       // alice was told bobby came online
        second.send(Protocol.LOGIN, "5", "alice", "password1");
        assertEquals("SESSION_REPLACED", first.nextPipe());
        assertTrue(first.closedByServer());
        assertEquals("LOGIN_OK|5|alice", second.nextPipe());
        assertEquals("USER_STATUS|alice|1", watcher.nextPipe());     // presence re-announced for the new session
    }

    @Test void replacedConnectionDoesNotMarkTheNewSessionOffline() throws Exception {
        RawClient first = connect(), second = connect(), watcher = connect();
        first.registerAndLogin("alice", "password1");
        watcher.registerAndLogin("bobby", "password1");
        assertEquals("USER_STATUS|bobby|1", first.nextPipe());
        second.send(Protocol.LOGIN, "5", "alice", "password1");
        assertEquals("LOGIN_OK|5|alice", second.nextPipe());
        assertEquals("USER_STATUS|alice|1", watcher.nextPipe());
        assertEquals("SESSION_REPLACED", first.nextPipe());
        assertTrue(first.closedByServer());
        assertTrue(watcher.silentFor(500), "no USER_STATUS alice 0 may follow the replacement");
        second.send(Protocol.PING, "6");                              // the new session is alive
        assertEquals("PONG|6", second.nextPipe());
        second.close();
        assertEquals("USER_STATUS|alice|0", watcher.nextPipe());     // a real disconnect of the live session IS announced
    }

    // ------------------------------------------------------------------ concurrency
    @Test void manyClientsCanRegisterAndLoginAtTheSameTime() throws Exception {
        int n = 40;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        List<RawClient> mine = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            String name = "user" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    RawClient c = new RawClient(port);
                    mine.add(c);
                    go.await();
                    c.registerAndLogin(name, "password1");
                    ok.incrementAndGet();
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally { done.countDown(); }
            });
        }
        go.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(n, ok.get());
        for (RawClient c : mine) c.close();
    }

    @Test void aClientThatSendsGarbageBytesDoesNotAffectOthers() throws Exception {
        RawClient good = connect();
        good.registerAndLogin("alice", "password1");
        RawClient bad = connect();
        bad.sendRaw("\u0000\u0001\u0002 garbage without tabs");
        assertEquals("ERROR|0|UNKNOWN_COMMAND", bad.nextPipe());
        good.send(Protocol.PING, "9");
        assertEquals("PONG|9", good.nextPipe());
    }

    @Test void codecIsUsedForTheHelperItself() {
        assertEquals("PING\t1", Codec.encode(Protocol.PING, "1"));
    }
}
