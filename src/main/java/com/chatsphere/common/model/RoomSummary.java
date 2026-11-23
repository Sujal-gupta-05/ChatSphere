package com.chatsphere.common.model;
/** lastText is already a one-line preview (Validation.preview); lastTimestamp is 0 when the room has no messages. */
public record RoomSummary(String name, int memberCount, boolean joined, int unread, String lastText, long lastTimestamp) {}
