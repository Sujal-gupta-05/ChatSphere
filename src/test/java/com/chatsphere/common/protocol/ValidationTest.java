package com.chatsphere.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {

    // ------------------------------------------------------------------ username: 3-20 of [A-Za-z0-9_]
    @Test void usernameLengthBoundaries() {
        assertFalse(Validation.isValidUsername("ab"));
        assertTrue(Validation.isValidUsername("abc"));
        assertTrue(Validation.isValidUsername("a".repeat(20)));
        assertFalse(Validation.isValidUsername("a".repeat(21)));
    }

    @Test void usernameAllowedAndForbiddenCharacters() {
        assertTrue(Validation.isValidUsername("Alice_01"));
        assertTrue(Validation.isValidUsername("___"));
        assertFalse(Validation.isValidUsername("al ice"));
        assertFalse(Validation.isValidUsername("alice!"));
        assertFalse(Validation.isValidUsername("al-ice"));
        assertFalse(Validation.isValidUsername("\u00e9l\u00e8ve"));
        assertFalse(Validation.isValidUsername("abc\n"));
        assertFalse(Validation.isValidUsername("abc\t"));
    }

    @Test void usernameNullAndEmpty() {
        assertFalse(Validation.isValidUsername(null));
        assertFalse(Validation.isValidUsername(""));
    }

    @Test void usernamesAreCaseSensitiveValuesNotNormalised() {
        assertTrue(Validation.isValidUsername("alice"));
        assertTrue(Validation.isValidUsername("Alice"));
    }

    // ------------------------------------------------------------------ room: 3-30 of [A-Za-z0-9_-]
    @Test void roomNameBoundaries() {
        assertFalse(Validation.isValidRoomName("ab"));
        assertTrue(Validation.isValidRoomName("abc"));
        assertTrue(Validation.isValidRoomName("r".repeat(30)));
        assertFalse(Validation.isValidRoomName("r".repeat(31)));
    }

    @Test void roomNameCharacters() {
        assertTrue(Validation.isValidRoomName("my-room_2"));
        assertTrue(Validation.isValidRoomName("---"));
        assertFalse(Validation.isValidRoomName("my room"));
        assertFalse(Validation.isValidRoomName("#general"));
        assertFalse(Validation.isValidRoomName("room\n"));
        assertFalse(Validation.isValidRoomName(null));
        assertFalse(Validation.isValidRoomName(""));
    }

    // ------------------------------------------------------------------ password: 8-100 any characters
    @Test void passwordLengthBoundaries() {
        assertFalse(Validation.isValidPassword("1234567"));
        assertTrue(Validation.isValidPassword("12345678"));
        assertTrue(Validation.isValidPassword("p".repeat(100)));
        assertFalse(Validation.isValidPassword("p".repeat(101)));
    }

    @Test void passwordAcceptsAnyCharacters() {
        assertTrue(Validation.isValidPassword("pass word!"));
        assertTrue(Validation.isValidPassword("\u00fc\u00f1\u00ee\u00e7\u00f8d\u00e9!"));
        assertTrue(Validation.isValidPassword("        "));          // 8 spaces: allowed by the contract
    }

    @Test void passwordNullAndEmpty() {
        assertFalse(Validation.isValidPassword(null));
        assertFalse(Validation.isValidPassword(""));
    }

    // ------------------------------------------------------------------ message: not blank, <= 2000
    @Test void messageLengthBoundaries() {
        assertTrue(Validation.isValidMessage("x"));
        assertTrue(Validation.isValidMessage("x".repeat(Limits.MESSAGE_MAX)));
        assertFalse(Validation.isValidMessage("x".repeat(Limits.MESSAGE_MAX + 1)));
    }

    @Test void blankMessagesAreInvalid() {
        assertFalse(Validation.isValidMessage(null));
        assertFalse(Validation.isValidMessage(""));
        assertFalse(Validation.isValidMessage("   "));
        assertFalse(Validation.isValidMessage("\n\t \r\n"));
    }

    @Test void messageMayContainNewlinesAndEmoji() {
        assertTrue(Validation.isValidMessage("line one\nline two\nline three"));
        assertTrue(Validation.isValidMessage("hi \uD83D\uDE00"));
        assertTrue(Validation.isValidMessage("  padded  "));
    }

    // ------------------------------------------------------------------ preview
    @Test void previewCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", Validation.preview("  a \n\t b\r\n c  "));
    }

    @Test void previewOfNullIsEmpty() { assertEquals("", Validation.preview(null)); }

    @Test void previewOfBlankIsEmpty() { assertEquals("", Validation.preview("  \n  ")); }

    @Test void previewExactlyMaxIsUnchanged() {
        String s = "x".repeat(Limits.PREVIEW_MAX);
        assertEquals(s, Validation.preview(s));
    }

    @Test void previewLongerThanMaxIsCutWithEllipsisToExactlyMax() {
        String p = Validation.preview("x".repeat(Limits.PREVIEW_MAX + 1));
        assertEquals(Limits.PREVIEW_MAX, p.length());
        assertTrue(p.endsWith("\u2026"));
        assertEquals("x".repeat(Limits.PREVIEW_MAX - 1) + "\u2026", p);
    }

    @Test void previewCollapsesBeforeMeasuring() {
        String text = "word ".repeat(30);                 // 150 chars with spaces, collapses to 149 after trim
        String p = Validation.preview(text);
        assertEquals(Limits.PREVIEW_MAX, p.length());
        assertFalse(p.contains("  "));
    }
}
