package com.chatsphere.client.net;

import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Test helper: a scripted TCP server. Records every line the client sends and lets the test decide what to answer. */
final class FakeServer implements AutoCloseable {
    interface Handler { void onFrame(Socket client, List<String> fields) throws IOException; }

    final ServerSocket serverSocket;
    final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
    private final Handler handler;
    private final Consumer<Socket> onConnect;

    FakeServer(Handler handler) throws IOException { this(handler, s -> { }); }

    FakeServer(Handler handler, Consumer<Socket> onConnect) throws IOException {
        this.handler = handler;
        this.onConnect = onConnect;
        serverSocket = new ServerSocket(0);
        Thread t = new Thread(this::acceptLoop, "fake-server-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() { return serverSocket.getLocalPort(); }

    private void acceptLoop() {
        try {
            while (true) {
                Socket s = serverSocket.accept();
                sockets.add(s);
                Thread t = new Thread(() -> serve(s), "fake-server-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException ignored) { }
    }

    private void serve(Socket s) {
        try {
            onConnect.accept(s);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                lines.add(line);
                try {
                    handler.onFrame(s, Codec.decode(line));
                } catch (ChatException ignored) { /* test sent something undecodable: just record it */ }
            }
        } catch (IOException ignored) { }
    }

    static synchronized void send(Socket s, String rawLine) throws IOException {
        s.getOutputStream().write((rawLine + "\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
    }

    static String frame(String cmd, String... fields) { return Codec.encode(cmd, fields); }

    String nextLine(long ms) throws InterruptedException { return lines.poll(ms, TimeUnit.MILLISECONDS); }

    void closeClients() {
        for (Socket s : sockets) { try { s.close(); } catch (IOException ignored) { } }
    }

    @Override public void close() {
        try { serverSocket.close(); } catch (IOException ignored) { }
        closeClients();
    }
}
