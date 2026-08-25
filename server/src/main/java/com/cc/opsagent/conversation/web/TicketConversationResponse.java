package com.cc.opsagent.conversation.web;

import com.cc.opsagent.conversation.application.ConversationView;

import java.time.Instant;
import java.util.List;

public record TicketConversationResponse(
        long id,
        long ticketId,
        String summary,
        Long summarizedThroughMessageId,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessageResponse> messages) {

    public static TicketConversationResponse from(ConversationView view) {
        var conversation = view.conversation();
        return new TicketConversationResponse(
                conversation.id(), conversation.ticketId(),
                conversation.summary(),
                conversation.summarizedThroughMessageId(),
                conversation.createdAt(), conversation.updatedAt(),
                view.messages().stream()
                        .map(ConversationMessageResponse::from)
                        .toList());
    }
}
