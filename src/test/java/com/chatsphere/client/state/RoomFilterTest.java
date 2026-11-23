package com.chatsphere.client.state;

import com.chatsphere.common.model.RoomSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomFilterTest {
    private static RoomSummary room(String name) { return new RoomSummary(name, 1, false, 0, "", 0); }
    private final List<RoomSummary> rooms = List.of(room("general"), room("Random"), room("dev-ops"));

    @Test void blankKeepsEverythingInOrder() {
        assertEquals(rooms, RoomFilter.filter(rooms, ""));
        assertEquals(rooms, RoomFilter.filter(rooms, "   "));
        assertEquals(rooms, RoomFilter.filter(rooms, null));
    }

    @Test void matchesSubstringIgnoringCase() {
        assertEquals(List.of(room("Random")), RoomFilter.filter(rooms, "RAND"));
        assertEquals(List.of(room("dev-ops")), RoomFilter.filter(rooms, " ops "));
    }

    @Test void noMatchGivesEmptyList() {
        assertEquals(List.of(), RoomFilter.filter(rooms, "zzz"));
    }
}
