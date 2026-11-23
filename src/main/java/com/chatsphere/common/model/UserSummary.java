package com.chatsphere.common.model;
/** A registered user other than me. ChatService returns online=false; the server's session registry fills it in. */
public record UserSummary(String username, boolean online, int unread, String lastText, long lastTimestamp) {}
