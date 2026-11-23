package com.chatsphere.common.protocol;

/** Unchecked business/protocol error carrying an ErrorCode. Thrown by ChatService and by Codec. */
public class ChatException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    public ChatException(ErrorCode code) { super(code.name()); this.code = code; }
    public ChatException(ErrorCode code, String detail) { super(code.name() + ": " + detail); this.code = code; }

    public ErrorCode code() { return code; }
}
