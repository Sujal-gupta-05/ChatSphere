package com.chatsphere.common.protocol;

import com.chatsphere.GoldenFrames;
import com.chatsphere.common.model.TargetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodecTest {

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(ChatException.class, r::run).code();
    }

    // ------------------------------------------------------------------ escaping
    @Test void escapeHandlesBackslashTabNewlineAndCarriageReturn() {
        assertEquals("a\\tb\\nc\\rd\\\\e", Codec.escape("a\tb\nc\rd\\e"));
    }

    @Test void escapeLeavesPlainTextAndUnicodeAlone() {
        assertEquals("hello world", Codec.escape("hello world"));
        assertEquals("h\u00e9llo \uD83D\uDE00", Codec.escape("h\u00e9llo \uD83D\uDE00"));
    }

    @Test void escapeOfNullIsEmpty() { assertEquals("", Codec.escape(null)); }

    @Test void escapedTextNeverContainsRawTabOrNewline() {
        String e = Codec.escape("line1\nline2\tcol\r\n");
        assertFalse(e.contains("\n"));
        assertFalse(e.contains("\t"));
        assertFalse(e.contains("\r"));
    }

    @Test void unescapeReversesEscape() {
        assertEquals("a\tb\nc\rd\\e", Codec.unescape("a\\tb\\nc\\rd\\\\e"));
    }

    @Test void unescapeWithoutBackslashReturnsSameText() {
        assertEquals("plain", Codec.unescape("plain"));
    }

    @Test void danglingBackslashIsBadRequest() {
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.unescape("abc\\")));
    }

    @Test void unknownEscapeIsBadRequest() {
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.unescape("a\\xb")));
    }

    @Test void roundTripOfTrickyStrings() {
        String[] samples = {"", "\\", "\\\\", "\\n", "\\\\n", "\n", "\t", "\r", "\r\n", "\t\t\t", "a\\", "C:\\new\\table",
                "\\t", "line one\nline two", "emoji \uD83D\uDE00 and \u00fc\u00f1", " leading and trailing  "};
        for (String s : samples) {
            assertEquals(s, Codec.unescape(Codec.escape(s)), "round trip of <" + s + ">");
        }
    }

    @Test void backslashFollowedByLetterNIsNotANewline() {
        // The pitfall from the PDF: the two characters '\' 'n' typed by a user must come back as '\' 'n', not as LF.
        String typed = "C:\\new";                                  // C : \ n e w
        String frame = Codec.encode(Protocol.MSG, "7", "general", typed);
        assertFalse(frame.contains("\n"));
        List<String> f = Codec.decode(frame);
        assertEquals(typed, f.get(3));
        assertFalse(f.get(3).contains("\n"));
    }

    // ------------------------------------------------------------------ frames
    @Test void encodeJoinsFieldsWithSingleTab() {
        assertEquals("LOGIN\t1\tdemo\tpassword1", Codec.encode(Protocol.LOGIN, "1", "demo", "password1"));
    }

    @Test void encodeWithoutFieldsHasNoTab() {
        assertEquals("SESSION_REPLACED", Codec.encode(Protocol.SESSION_REPLACED));
    }

    @Test void encodeListAndVarargsAgree() {
        assertEquals(Codec.encode("X", "a", "b"), Codec.encode("X", List.of("a", "b")));
    }

    @Test void encodeEscapesInsideFields() {
        assertEquals("MSG\t7\tgeneral\tline one\\nline two", Codec.encode(Protocol.MSG, "7", "general", "line one\nline two"));
    }

    @Test void emptyFieldsAreKept() {
        List<String> f = Codec.decode("a\t\tb");
        assertEquals(List.of("a", "", "b"), f);
        assertEquals("a\t\tb", Codec.encode("a", "", "b"));
    }

    @Test void trailingEmptyFieldIsKept() {
        assertEquals(List.of("x", ""), Codec.decode("x\t"));
        assertEquals(List.of("x", "", ""), Codec.decode("x\t\t"));
    }

    @Test void decodeUnescapesFields() {
        List<String> f = Codec.decode("MSG\t7\tgeneral\tline one\\nline two");
        assertEquals(4, f.size());
        assertEquals("line one\nline two", f.get(3));
    }

    @Test void decodeKeepsBackslashAndLetterN() {
        // "a\\nb" on the wire = a, backslash, n, b
        assertEquals("a\\nb", Codec.decode("T\ta\\\\nb").get(1));
    }

    @Test void decodeRejectsNullEmptyAndMissingCommand() {
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.decode(null)));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.decode("")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.decode("\tx")));
    }

    @Test void decodeRejectsBadEscapeInsideFrame() {
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.decode("MSG\t1\tr\tbad\\q")));
    }

    @Test void commandItselfIsEscapedAndUnescaped() {
        assertEquals("A\\tB", Codec.encode("A\tB"));
        assertEquals("A\tB", Codec.decode("A\\tB").get(0));
    }

    // ------------------------------------------------------------------ typed helpers
    @Test void strHelpers() {
        assertEquals("42", Codec.str(42L));
        assertEquals("-7", Codec.str(-7L));
        assertEquals("1", Codec.str(true));
        assertEquals("0", Codec.str(false));
    }

    @Test void toLongAcceptsNumbersAndRejectsGarbage() {
        assertEquals(1767225600000L, Codec.toLong("1767225600000"));
        assertEquals(-5L, Codec.toLong("-5"));
        assertEquals(0L, Codec.toLong("0"));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toLong("abc")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toLong("")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toLong(" 5")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toLong("99999999999999999999")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toLong(null)));
    }

    @Test void toIntBoundaries() {
        assertEquals(Integer.MAX_VALUE, Codec.toInt("2147483647"));
        assertEquals(Integer.MIN_VALUE, Codec.toInt("-2147483648"));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toInt("2147483648")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toInt("1.5")));
    }

    @Test void toBoolOnlyAcceptsZeroAndOne() {
        assertTrue(Codec.toBool("1"));
        assertFalse(Codec.toBool("0"));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toBool("true")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toBool("")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toBool("2")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toBool(null)));
    }

    @Test void toEnumIsCaseSensitiveAndRejectsUnknown() {
        assertEquals(TargetType.ROOM, Codec.toEnum(TargetType.class, "ROOM"));
        assertEquals(TargetType.PRIVATE, Codec.toEnum(TargetType.class, "PRIVATE"));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toEnum(TargetType.class, "room")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toEnum(TargetType.class, "")));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.toEnum(TargetType.class, null)));
    }

    @Test void requireSizeChecksExactCount() {
        List<String> f = List.of("PING", "1");
        Codec.requireSize(f, 2);
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.requireSize(f, 3)));
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(() -> Codec.requireSize(f, 1)));
    }

    // ------------------------------------------------------------------ sizes
    @Test void longestLegalMessageOfSpecialCharactersFitsInOneFrame() {
        String worst = "\t".repeat(Limits.MESSAGE_MAX);                 // every char becomes two chars
        String frame = Codec.encode(Protocol.MSG, "99999999", "a-room-name-of-30-characters-xx", worst);
        assertTrue(frame.length() < Limits.MAX_LINE_BYTES, "length " + frame.length());
        assertEquals(worst, Codec.decode(frame).get(3));
    }

    @Test void veryLongFieldRoundTrips() {
        String big = "\\".repeat(30_000);                               // encodes to 60 000 chars
        String frame = Codec.encode("X", big);
        assertEquals(big, Codec.decode(frame).get(1));
    }

    // ------------------------------------------------------------------ golden frames
    private static final Map<String, Integer> EXPECTED_FIELDS = Map.ofEntries(
            Map.entry("LOGIN", 4), Map.entry("MSG", 4), Map.entry("SEARCH", 9),
            Map.entry("LOGIN_OK", 3), Map.entry("ERROR", 3), Map.entry("SENT", 4),
            Map.entry("ROOM_LIST_RESULT", 16), Map.entry("USER_LIST_RESULT", 13),
            Map.entry("HISTORY_RESULT", 17), Map.entry("SEARCH_RESULT", 9),
            Map.entry("MSG_PUSH", 6), Map.entry("PM_PUSH", 5), Map.entry("USER_STATUS", 3),
            Map.entry("TYPING_PUSH", 4), Map.entry("ACK_PUSH", 6),
            Map.entry("ROOM_MEMBERS_CHANGED", 4), Map.entry("SESSION_REPLACED", 1));

    @Test void everyGoldenFrameDecodesToTheListedFieldCountAndReEncodesByteForByte() {
        List<String> all = new java.util.ArrayList<>(GoldenFrames.clientFrames());
        all.addAll(GoldenFrames.serverFrames());
        assertEquals(EXPECTED_FIELDS.size(), all.size(), "number of golden frames");
        for (String line : all) {
            List<String> f = Codec.decode(line);
            assertEquals(EXPECTED_FIELDS.get(f.get(0)).intValue(), f.size(), "fields of " + f.get(0));
            assertEquals(line, Codec.encode(f.get(0), f.subList(1, f.size())), "re-encode of " + f.get(0));
        }
    }

    @Test void goldenMultiLineMessageDecodesToRealNewline() {
        String line = GoldenFrames.clientFrames().stream().filter(l -> l.startsWith("MSG")).findFirst().orElseThrow();
        assertEquals("line one\nline two", Codec.decode(line).get(3));
    }
}
