package com.chatsphere.server.net;

import com.chatsphere.server.service.ChatService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The TCP server. start() binds the port and returns at once; a dedicated thread runs the accept loop and every
 * accepted connection gets its own ClientHandler on its own virtual thread.
 */
public final class ChatServer {
    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());

    private final int requestedPort;
    private final ChatService service;
    private final SessionRegistry registry = new SessionRegistry();
    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private boolean started;
    private volatile boolean running;

    /** port 0 = pick any free port (start() returns the real one). */
    public ChatServer(int port, ChatService service) {
        this.requestedPort = port;
        this.service = service;
    }

    /** Binds, starts accepting in the background and returns the bound port. Can be called once. */
    public synchronized int start() throws IOException {
        if (started) throw new IllegalStateException("ChatServer was already started");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);                       // lets tests restart a server on the same port
            ss.bind(new InetSocketAddress(requestedPort));
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        started = true;
        running = true;
        serverSocket = ss;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        acceptThread = new Thread(this::acceptLoop, "chat-accept");
        acceptThread.start();
        int port = ss.getLocalPort();
        LOG.info("ChatServer listening on port " + port);
        return port;
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
                ClientHandler h = new ClientHandler(s, service, registry);
                handlers.add(h);
                executor.execute(() -> {
                    try { h.run(); } finally { handlers.remove(h); }
                });
            } catch (SocketException e) {
                if (!running) return;                       // stop() closed the server socket: normal exit
                LOG.log(Level.WARNING, "Accept failed", e);
                sleepQuietly(50);
            } catch (RejectedExecutionException e) {
                closeQuietly(s);                            // executor was shut down while we were accepting
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Accept failed", e);
                closeQuietly(s);
                sleepQuietly(50);
            }
        }
    }

    /** Stops accepting, disconnects every client, ends all handler threads. Safe to call more than once. */
    public void stop() {
        ExecutorService ex;
        Thread at;
        synchronized (this) {
            if (!running) return;
            running = false;
            ex = executor;
            at = acceptThread;
        }
        closeQuietly(serverSocket);
        try { at.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (ClientHandler h : handlers) h.forceClose();
        ex.shutdown();
        try {
            if (!ex.awaitTermination(3, TimeUnit.SECONDS)) ex.shutdownNow();
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("ChatServer stopped");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
