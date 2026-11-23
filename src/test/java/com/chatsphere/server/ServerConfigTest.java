package com.chatsphere.server;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    @Test void builtInDefaultsWhenNothingIsConfigured() {
        ServerConfig c = ServerConfig.parse(new String[0], new Properties());
        assertEquals(5050, c.port());
        assertEquals("chatsphere.db", c.dbPath());
        assertFalse(c.memory());
    }

    @Test void configPropertiesOverrideDefaults() {
        ServerConfig c = ServerConfig.parse(new String[0], props("server.port", "6001", "server.db", "my.db"));
        assertEquals(6001, c.port());
        assertEquals("my.db", c.dbPath());
    }

    @Test void flagsOverrideProperties() {
        ServerConfig c = ServerConfig.parse(new String[]{"--port=7000", "--db=flag.db", "--memory"},
                props("server.port", "6001", "server.db", "my.db"));
        assertEquals(7000, c.port());
        assertEquals("flag.db", c.dbPath());
        assertTrue(c.memory());
    }

    @Test void portZeroMeansAnyFreePort() {
        assertEquals(0, ServerConfig.parse(new String[]{"--port=0"}, new Properties()).port());
    }

    @Test void badPortIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--port=abc"}, new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--port=70000"}, new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--port=-1"}, new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[0], props("server.port", "x")));
    }

    @Test void unknownFlagAndEmptyDbAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--bogus"}, new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--db="}, new Properties()));
    }

    @Test void loadReadsTheRealConfigPropertiesFile() {
        ServerConfig c = ServerConfig.load(new String[0]);
        assertEquals(5050, c.port());
        assertEquals("chatsphere.db", c.dbPath());
    }
}
