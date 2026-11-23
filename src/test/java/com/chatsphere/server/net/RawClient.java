package com.chatsphere.server.net;

import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/** Test helper: a bare TCP client that speaks the protocol using only Codec. Every read has a timeout so a missing frame fails the test. */
public final class RawClient implements AutoCloseable {
    public static final int TIMEOUT_MS = 2000;
    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;

    public RawClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
    }

    public void send(String command, String... fields) throws IOException { sendRaw(Codec.encode(command, fields)); }

    public synchronized void sendRaw(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Next frame as decoded fields; fails the test if nothing arrives within the timeout. */
    public List<String> next() throws IOException {
        try {
            String line = in.readLine();
            if (line == null) fail("connection closed while waiting for a frame");
            return Codec.decode(line);
        } catch (SocketTimeoutException e) {
            fail("timed out waiting for a frame");
            return null;
        }
    }

    /** Next frame joined with '|' (the ConsoleClient format) for readable assertions. */
    public String nextPipe() throws IOException {
        List<String> f = next();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Codec.escape(f.get(i)));          // line breaks shown as \n, like ConsoleClient
        }
        return sb.toString();
    }

    /** True if the server closes the connection (EOF or reset) within the timeout. Frames still pending are skipped. */
    public boolean closedByServer() {
        try {
            while (true) {
                String line = in.readLine();
                if (line == null) return true;
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** True if no frame arrives within ms milliseconds. */
    public boolean silentFor(long ms) throws IOException {
        socket.setSoTimeout((int) ms);
        try {
            in.readLine();                      // a frame or EOF arrived: not silent
            return false;
        } catch (SocketTimeoutException e) {
            return true;
        } finally {
            socket.setSoTimeout(TIMEOUT_MS);
        }
    }

    /** register + login in one go; consumes the two replies. */
    public void registerAndLogin(String user, String password) throws IOException {
        send(Protocol.REGISTER, "1", user, password);
        String r = nextPipe();
        if (!r.equals("OK|1")) fail("register failed: " + r);
        send(Protocol.LOGIN, "2", user, password);
        r = nextPipe();
        if (!r.equals("LOGIN_OK|2|" + user)) fail("login failed: " + r);
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
