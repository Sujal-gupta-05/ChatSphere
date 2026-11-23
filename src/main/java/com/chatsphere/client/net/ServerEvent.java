package com.chatsphere.client.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ErrorCode;
import java.util.List;

/** Everything the server (or the connection layer) can tell the UI without being asked. Delivered on the FX thread. */
public sealed interface ServerEvent {
    /** retryInSeconds is meaningful only for RECONNECTING; detail is a short English hint (may be empty). */
    record ConnectionStateChanged(ConnectionState state, int retryInSeconds, String detail) implements ServerEvent {}
    /** Emitted after an automatic reconnect AND silent re-login succeeded. ViewModels must refetch lists and the open conversation tail. */
    record SessionRestored() implements ServerEvent {}
    /** The session is over (SESSION_REPLACED, or credentials rejected on reconnect). UI must return to Auth and show reason. */
    record SessionEnded(String reason) implements ServerEvent {}
    /** A message in a room (never your own). */
    record RoomMessage(String room, MessageRecord message) implements ServerEvent {}
    /** An incoming private message; peer is the sender. */
    record PrivateMessage(String peer, MessageRecord message) implements ServerEvent {}
    record UserStatus(String username, boolean online) implements ServerEvent {}
    /** For PRIVATE, target equals user. */
    record Typing(TargetType type, String target, String user) implements ServerEvent {}
    /** Messages YOU sent to `peer` changed to DELIVERED or READ. */
    record StatusUpdate(DeliveryStatus status, String peer, List<Long> messageIds) implements ServerEvent {}
    record RoomMembersChanged(String room, String user, boolean joined) implements ServerEvent {}
    /** Unsolicited error (reqId 0). */
    record ServerError(ErrorCode code) implements ServerEvent {}
}
