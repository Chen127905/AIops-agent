package com.cc.opsagent.conversation.domain;

import java.time.Instant;

public record TicketConversation(
        long id,
        long tenantId,
        long ticketId,
        String summary,
        Long summarizedThroughMessageId,
        Instant createdAt,
        Instant updatedAt) {
}
