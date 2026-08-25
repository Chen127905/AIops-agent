package com.cc.opsagent.conversation.domain;

import java.time.Instant;

public record ConversationMessage(
        long id,
        long conversationId,
        ConversationRole role,
        ConversationMessageStatus status,
        String content,
        String provider,
        String modelName,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        Instant createdAt) {
}
