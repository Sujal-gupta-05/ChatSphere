package com.chatsphere.common.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame codec. One frame = one line of UTF-8 text terminated by '\n'.
 * Fields are separated by TAB. Inside a field: '\' -> "\\", TAB -> "\t", LF -> "\n", CR -> "\r".
 * Field 0 is always the command/event name. Both ends MUST use only this class to build and read frames.
 */
public final class Codec {
    private Codec() {}
    public static final char SEP = '\t';

    // ---------- escaping ----------
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (i + 1 >= s.length()) throw new ChatException(ErrorCode.BAD_REQUEST, "dangling escape");
            char n = s.charAt(++i);
            switch (n) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> throw new ChatException(ErrorCode.BAD_REQUEST, "bad escape \\" + n);
            }
        }
        return sb.toString();
    }

    // ---------- frames ----------
    /** Builds a frame WITHOUT the trailing newline. The caller appends '\n' when writing. */
    public static String encode(String command, List<String> fields) {
        StringBuilder sb = new StringBuilder(escape(command));
        for (String f : fields) sb.append(SEP).append(escape(f));
        return sb.toString();
    }

    public static String encode(String command, String... fields) {
        return encode(command, List.of(fields));
    }

    /** Splits a received line (no trailing newline) into unescaped fields. Index 0 is the command. */
    public static List<String> decode(String line) {
        if (line == null || line.isEmpty()) throw new ChatException(ErrorCode.BAD_REQUEST, "empty frame");
        String[] parts = line.split("\t", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(unescape(p));
        if (out.get(0).isEmpty()) throw new ChatException(ErrorCode.BAD_REQUEST, "missing command");
        return out;
    }

    // ---------- typed field helpers ----------
    public static String str(long v) { return Long.toString(v); }
    public static String str(boolean b) { return b ? "1" : "0"; }

    public static long toLong(String f) {
        try { return Long.parseLong(f); } catch (NumberFormatException e) { throw new ChatException(ErrorCode.BAD_REQUEST, "number expected"); }
    }
    public static int toInt(String f) {
        try { return Integer.parseInt(f); } catch (NumberFormatException e) { throw new ChatException(ErrorCode.BAD_REQUEST, "number expected"); }
    }
    public static boolean toBool(String f) {
        if ("1".equals(f)) return true;
        if ("0".equals(f)) return false;
        throw new ChatException(ErrorCode.BAD_REQUEST, "0 or 1 expected");
    }
    public static <E extends Enum<E>> E toEnum(Class<E> type, String f) {
        try { return Enum.valueOf(type, f); } catch (IllegalArgumentException | NullPointerException e) { throw new ChatException(ErrorCode.BAD_REQUEST, "bad value"); }
    }
    /** Throws BAD_REQUEST unless the frame has exactly n fields (including the command). */
    public static void requireSize(List<String> f, int n) {
        if (f.size() != n) throw new ChatException(ErrorCode.BAD_REQUEST, "expected " + n + " fields, got " + f.size());
    }
}
