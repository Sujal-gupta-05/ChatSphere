package com.chatsphere.common.model;
import java.util.List;
/**
 * messages: ascending by id. hasMore: older messages exist beyond this page.
 * firstUnreadId: id of the oldest unread message in the conversation, or 0 (only filled when beforeId == 0 or for AROUND).
 */
public record HistoryPage(List<MessageRecord> messages, boolean hasMore, long firstUnreadId) {}
