package com.chatsphere.client.state;

import java.util.ArrayList;
import java.util.List;

/** Owned by Person 3. Pure helper: cuts a message down around the first keyword hit and marks every hit. No JavaFX. */
public final class SearchSnippet {
    private SearchSnippet() {}

    private static final String ELLIPSIS = "\u2026";

    public record Segment(String text, boolean match) {}

    /**
     * Whitespace is collapsed. The result shows up to {@code before} characters before and {@code after} after the first
     * match, with an ellipsis where text was cut. Matching ignores case. No match: the start of the text, shortened.
     */
    public static List<Segment> of(String text, String keyword, int before, int after) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        String kw = keyword == null ? "" : keyword.trim();
        if (t.isEmpty()) return List.of();
        int first = kw.isEmpty() ? -1 : indexOf(t, kw, 0);
        if (first < 0) return List.of(new Segment(cut(t, before + after), false));

        int start = Math.max(0, first - before);
        int end = Math.min(t.length(), first + kw.length() + after);
        List<Segment> out = new ArrayList<>();
        StringBuilder plain = new StringBuilder(start > 0 ? ELLIPSIS : "");
        int pos = start;
        while (pos < end) {
            int m = indexOf(t, kw, pos);
            if (m < 0 || m >= end) {
                plain.append(t, pos, end);
                break;
            }
            plain.append(t, pos, m);
            if (plain.length() > 0) {
                out.add(new Segment(plain.toString(), false));
                plain.setLength(0);
            }
            out.add(new Segment(t.substring(m, m + kw.length()), true));
            pos = m + kw.length();
        }
        if (end < t.length()) plain.append(ELLIPSIS);
        if (plain.length() > 0) out.add(new Segment(plain.toString(), false));
        return out;
    }

    private static String cut(String t, int max) {
        return t.length() <= max ? t : t.substring(0, max - 1) + ELLIPSIS;
    }

    private static int indexOf(String text, String kw, int from) {
        for (int i = from; i + kw.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, kw, 0, kw.length())) return i;
        }
        return -1;
    }
}
