package com.cc.opsagent.conversation.application;

public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException(long ticketId) {
        super("ticket conversation is already processing: " + ticketId);
    }
}
