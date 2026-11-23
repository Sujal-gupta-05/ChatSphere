package com.chatsphere.common.model;
/** Server confirmation that a message is stored. */
public record SentAck(long id, long timestamp) {}
