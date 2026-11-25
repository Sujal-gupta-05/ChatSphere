package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.net.FakeChatClient;
import com.chatsphere.client.view.workspace.state.ChatListItem.*;
import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import com.chatsphere.common.model.DeliveryStatus;
import com.chatsphere.common.model.MessageRecord;
import com.chatsphere.common.model.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Person 4's ConversationViewModel.
 * Runs against FakeChatClient with synchronous execution (Runnable::run).
 */
public class ConversationViewModelTest {
    private FakeChatClient client;
    private ConversationViewModel vm;

    @BeforeEach
    void setUp() {
        client = new FakeChatClient(Runnable::run);
        AppContext.init(client);
        client.connect("localhost", 5050);
        client.login("demo", "password1".toCharArray()).join();
        vm = new ConversationViewModel(TargetType.ROOM, "general", client, "demo");
    }

    @Test
    @DisplayName("Initial load populates messages, day separators, and computes grouping")
    void initial_load_populates_items() {
        vm.loadInitial();

        assertFalse(vm.items().isEmpty(), "Items list should not be empty");
        assertTrue(vm.newestLoadedId() > 0, "Newest loaded id should be positive");

        boolean hasDaySep = vm.items().stream().anyMatch(it -> it instanceof DaySeparatorItem);
        assertTrue(hasDaySep, "Should contain at least one day separator");

        long msgCount = vm.items().stream().filter(it -> it instanceof MessageItem).count();
        assertTrue(msgCount >= 3, "Seed data has 3 messages in general");
    }

    @Test
    @DisplayName("Optimistic send appends PENDING message and upgrades to SENT on ack")
    void optimistic_send_upgrades_to_sent() {
        vm.loadInitial();
        int initialSize = vm.items().size();

        vm.sendMessage("Hello test message");

        assertEquals(initialSize + 1, vm.items().size(), "One item should be added");
        MessageItem sent = (MessageItem) vm.items().get(vm.items().size() - 1);

        assertEquals("Hello test message", sent.text());
        assertTrue(sent.isOwn());
        assertEquals(DeliveryStatus.SENT, sent.status(), "Ack should transition status to SENT");
        assertNotNull(sent.id(), "Stored message should have positive ID");
    }

    @Test
    @DisplayName("Send failure with #fail transitions status to FAILED")
    void send_failure_transitions_to_failed() {
        vm.loadInitial();
        vm.sendMessage("This message will #fail");

        MessageItem sent = (MessageItem) vm.items().get(vm.items().size() - 1);
        assertEquals(DeliveryStatus.FAILED, sent.status(), "Message with #fail should fail");

        vm.retrySend(sent);
        assertEquals(DeliveryStatus.FAILED, sent.status());
    }

    @Test
    @DisplayName("Status update upgrades status and never downgrades")
    void status_update_upgrades_and_never_downgrades() {
        ConversationViewModel privateVm = new ConversationViewModel(TargetType.PRIVATE, "alice", client, "demo");
        privateVm.loadInitial();

        privateVm.sendMessage("Hi Alice");
        MessageItem sent = (MessageItem) privateVm.items().get(privateVm.items().size() - 1);
        assertEquals(DeliveryStatus.SENT, sent.status());

        privateVm.handleStatusUpdate(DeliveryStatus.DELIVERED, List.of(sent.id()));
        assertEquals(DeliveryStatus.DELIVERED, sent.status());

        privateVm.handleStatusUpdate(DeliveryStatus.READ, List.of(sent.id()));
        assertEquals(DeliveryStatus.READ, sent.status());

        privateVm.handleStatusUpdate(DeliveryStatus.DELIVERED, List.of(sent.id()));
        assertEquals(DeliveryStatus.READ, sent.status(), "Status must never downgrade");
    }

    @Test
    @DisplayName("Incoming message is appended and clears typing indicator")
    void incoming_message_appended_and_clears_typing() {
        vm.loadInitial();
        vm.handleUserTyping("bob");
        assertTrue(vm.typingUsers().contains("bob"));

        MessageRecord incoming = new MessageRecord(999, "bob", System.currentTimeMillis(), DeliveryStatus.SENT, "New from Bob");
        vm.handleIncomingMessage(incoming);

        assertFalse(vm.typingUsers().contains("bob"), "Incoming message should clear typing indicator");
        MessageItem last = (MessageItem) vm.items().get(vm.items().size() - 1);
        assertEquals(999L, last.id());
        assertEquals("New from Bob", last.text());
        assertFalse(last.isOwn());
    }

    @Test
    @DisplayName("Blank message send is ignored")
    void blank_message_send_is_ignored() {
        vm.loadInitial();
        int countBefore = vm.items().size();

        vm.sendMessage("   ");
        vm.sendMessage("");
        vm.sendMessage(null);

        assertEquals(countBefore, vm.items().size(), "Blank messages should not be sent");
    }

    @Test
    @DisplayName("Unread divider is inserted when firstUnreadId > 0")
    void unread_divider_inserted_for_unread_history() {
        ConversationViewModel privateVm = new ConversationViewModel(TargetType.PRIVATE, "bob", client, "demo");
        privateVm.loadInitial();

        boolean hasUnreadDivider = privateVm.items().stream().anyMatch(it -> it instanceof UnreadDividerItem);
        assertTrue(hasUnreadDivider, "Conversation with unread messages should render UnreadDivider");
    }
}
