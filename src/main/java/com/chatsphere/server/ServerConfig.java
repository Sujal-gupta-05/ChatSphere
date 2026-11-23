package com.chatsphere.server;

import com.chatsphere.common.protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Server settings. Order of precedence (highest first): command-line flags, config.properties, built-in defaults.
 *
 * Flags: --memory (use InMemoryChatService, no database file), --port=NNNN, --db=path.
 * config.properties keys used here: server.port, server.db.
 */
public final class ServerConfig {
    public static final String DEFAULT_DB = "chatsphere.db";

    private final int port;
    private final String dbPath;
    private final boolean memory;

    public ServerConfig(int port, String dbPath, boolean memory) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        this.port = port;
        this.dbPath = dbPath;
        this.memory = memory;
    }

    public int port() { return port; }
    public String dbPath() { return dbPath; }
    public boolean memory() { return memory; }

    /** Loads config.properties from the classpath (if present) and applies the command-line flags. */
    public static ServerConfig load(String[] args) {
        Properties props = new Properties();
        try (InputStream in = ServerConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            // Missing or unreadable config file is not fatal: defaults apply.
        }
        return parse(args, props);
    }

    /** Pure function (easy to unit test): flags override the given properties, which override the defaults. */
    public static ServerConfig parse(String[] args, Properties props) {
        int port = parsePort(props.getProperty("server.port"), Protocol.DEFAULT_PORT, "server.port");
        String db = props.getProperty("server.db", DEFAULT_DB).trim();
        boolean memory = false;
        for (String a : args) {
            if (a.equals("--memory")) memory = true;
            else if (a.startsWith("--port=")) port = parsePort(a.substring("--port=".length()), port, "--port");
            else if (a.startsWith("--db=")) db = a.substring("--db=".length()).trim();
            else throw new IllegalArgumentException("Unknown argument: " + a
                    + " (use --memory, --port=NNNN, --db=path)");
        }
        if (db.isEmpty()) throw new IllegalArgumentException("--db must not be empty");
        return new ServerConfig(port, db, memory);
    }

    private static int parsePort(String s, int fallback, String what) {
        if (s == null || s.isBlank()) return fallback;
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 0 || p > 65535) throw new IllegalArgumentException(what + " out of range: " + p);
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a number: " + s);
        }
    }
}
