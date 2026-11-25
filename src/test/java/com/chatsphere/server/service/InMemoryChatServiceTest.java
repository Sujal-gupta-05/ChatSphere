package com.chatsphere.server.service;

/** The reference implementation must satisfy the contract it defines. */
class InMemoryChatServiceTest extends AbstractChatServiceTest {
    @Override
    protected ChatService create() {
        return new InMemoryChatService();
    }
}
