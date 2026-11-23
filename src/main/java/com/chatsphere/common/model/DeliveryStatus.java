package com.chatsphere.common.model;
/** PENDING and FAILED exist only on the client. The wire and the database use SENT, DELIVERED, READ. */
public enum DeliveryStatus { PENDING, SENT, DELIVERED, READ, FAILED }
