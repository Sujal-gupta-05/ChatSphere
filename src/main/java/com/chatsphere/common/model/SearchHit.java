package com.chatsphere.common.model;
/** target = room name, or the other user's name for PRIVATE (from the searcher's point of view). */
public record SearchHit(long id, TargetType targetType, String target, String sender, long timestamp, String text) {}
