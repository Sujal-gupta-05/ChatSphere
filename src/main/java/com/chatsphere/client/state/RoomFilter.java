package com.chatsphere.client.state;

import com.chatsphere.common.model.RoomSummary;

import java.util.List;
import java.util.Locale;

/** Owned by Person 3. Pure helper for the Browse rooms filter field (no JavaFX). */
public final class RoomFilter {
    private RoomFilter() {}

    /** Rooms whose name contains the query, ignoring case. Blank query keeps everything. Order is preserved. */
    public static List<RoomSummary> filter(List<RoomSummary> rooms, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.copyOf(rooms);
        return rooms.stream().filter(r -> r.name().toLowerCase(Locale.ROOT).contains(q)).toList();
    }
}
