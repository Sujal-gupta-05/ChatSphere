package com.chatsphere.client;

import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorMessagesTest {
    @Test void codeOfUnwrapsCompletionException() {
        Throwable t = new CompletionException(new ChatException(ErrorCode.ROOM_EXISTS));
        assertEquals(ErrorCode.ROOM_EXISTS, ErrorMessages.codeOf(t));
        assertEquals("A room with that name already exists.", ErrorMessages.of(t));
    }

    @Test void codeOfIsNullForForeignExceptions() {
        assertNull(ErrorMessages.codeOf(new IllegalStateException("x")));
        assertEquals("Something went wrong. Try again.", ErrorMessages.of(new IllegalStateException("x")));
    }

    @Test void everyCodeHasCopy() {
        for (ErrorCode c : ErrorCode.values()) assertFalse(ErrorMessages.of(c).isBlank());
    }
}
