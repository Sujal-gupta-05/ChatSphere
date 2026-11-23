package com.chatsphere.client.state;

import com.chatsphere.client.component.PasswordField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigTest {
    @Test void portFallsBackToDefaultOnGarbage() {
        assertEquals(5050, ClientConfig.parsePort(null));
        assertEquals(5050, ClientConfig.parsePort("abc"));
        assertEquals(5050, ClientConfig.parsePort("70000"));
        assertEquals(6000, ClientConfig.parsePort(" 6000 "));
    }

    @Test void strengthLabels() {
        assertEquals("weak", PasswordField.strengthOf("abc"));
        assertEquals("weak", PasswordField.strengthOf("abcdefgh"));
        assertEquals("fair", PasswordField.strengthOf("abcdefgh1"));
        assertEquals("strong", PasswordField.strengthOf("abcdefgh1234"));
    }
}
