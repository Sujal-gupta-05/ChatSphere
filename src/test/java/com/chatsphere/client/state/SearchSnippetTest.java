package com.chatsphere.client.state;

import com.chatsphere.client.state.SearchSnippet.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchSnippetTest {
    @Test void marksMatchIgnoringCaseAndKeepsOriginalSpelling() {
        assertEquals(List.of(new Segment("Hello ", false), new Segment("Build", true), new Segment(" world", false)),
                SearchSnippet.of("Hello Build world", "build", 40, 100));
    }

    @Test void marksEveryOccurrence() {
        assertEquals(List.of(new Segment("ab", true), new Segment(" ", false), new Segment("ab", true)),
                SearchSnippet.of("ab ab", "ab", 40, 100));
    }

    @Test void noMatchGivesTheStartOfTheText() {
        assertEquals(List.of(new Segment("nothing here", false)), SearchSnippet.of("nothing here", "zzz", 40, 100));
    }

    @Test void collapsesWhitespaceAndLineBreaks() {
        assertEquals(List.of(new Segment("a ", false), new Segment("b", true)), SearchSnippet.of("a\n\n  b", "b", 40, 100));
    }

    @Test void cutsLongTextAroundTheMatchWithEllipses() {
        String text = "x".repeat(100) + " needle " + "y".repeat(200);
        List<Segment> segs = SearchSnippet.of(text, "needle", 10, 10);
        assertTrue(segs.get(0).text().startsWith("\u2026"));
        assertTrue(segs.get(segs.size() - 1).text().endsWith("\u2026"));
        assertTrue(segs.contains(new Segment("needle", true)));
        int total = segs.stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(total < 50, "window should be short, was " + total);
    }

    @Test void longTextWithoutMatchIsShortened() {
        List<Segment> segs = SearchSnippet.of("z".repeat(500), "q", 40, 100);
        assertEquals(1, segs.size());
        assertEquals(140, segs.get(0).text().length());
        assertTrue(segs.get(0).text().endsWith("\u2026"));
    }

    @Test void emptyInputGivesNothing() {
        assertEquals(List.of(), SearchSnippet.of(null, "a", 40, 100));
        assertEquals(List.of(), SearchSnippet.of("   ", "a", 40, 100));
    }

    @Test void blankKeywordMarksNothing() {
        assertEquals(List.of(new Segment("hello", false)), SearchSnippet.of("hello", "  ", 40, 100));
    }
}
