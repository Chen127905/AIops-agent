package com.cc.opsagent.conversation.application;

import com.cc.opsagent.conversation.domain.ConversationMessage;
import com.cc.opsagent.conversation.domain.TicketConversation;

import java.util.List;

public record ConversationView(
        TicketConversation conversation,
        List<ConversationMessage> messages) {

    public ConversationView {
        messages = List.copyOf(messages);
    }
}
