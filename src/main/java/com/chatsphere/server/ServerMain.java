package com.chatsphere.server;

import com.chatsphere.server.net.ChatServer;
import com.chatsphere.server.service.ChatService;
import com.chatsphere.server.service.ChatServiceImpl;
import com.chatsphere.server.service.InMemoryChatService;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Server entry point.
 *   mvn compile exec:java                              database-backed (ChatServiceImpl, file from config.properties)
 *   mvn compile exec:java -Dexec.args="--memory"       in-memory service, nothing is saved
 *   mvn compile exec:java -Dexec.args="--port=6000 --db=other.db"
 */
public class ServerMain {
    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws InterruptedException {
        ServerConfig cfg;
        try {
            cfg = ServerConfig.load(args);
        } catch (IllegalArgumentException e) {
            System.err.println("ChatSphere server: " + e.getMessage());
            System.exit(2);
            return;
        }

        ChatService service = cfg.memory() ? new InMemoryChatService() : new ChatServiceImpl(cfg.dbPath());
        ChatServer server = new ChatServer(cfg.port(), service);
        int port;
        try {
            port = server.start();
        } catch (IOException e) {
            System.err.println("ChatSphere server: cannot listen on port " + cfg.port() + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            if (service instanceof AutoCloseable c) {       // a database service may offer close(); harmless otherwise
                try { c.close(); } catch (Exception ignored) { }
            }
            stopped.countDown();
        }, "shutdown-hook"));

        LOG.info("ChatSphere server ready on port " + port + (cfg.memory() ? " (in-memory, nothing is saved)" : " (database: " + cfg.dbPath() + ")"));
        stopped.await();                                    // block until Ctrl+C triggers the shutdown hook
    }
}
