package com.cc.opsagent.conversation.web;

import com.cc.opsagent.conversation.domain.ConversationMessage;
import com.cc.opsagent.conversation.domain.ConversationMessageStatus;
import com.cc.opsagent.conversation.domain.ConversationRole;

import java.time.Instant;

public record ConversationMessageResponse(
        long id,
        ConversationRole role,
        ConversationMessageStatus status,
        String content,
        String provider,
        String modelName,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        Instant createdAt) {

    static ConversationMessageResponse from(ConversationMessage message) {
        return new ConversationMessageResponse(
                message.id(), message.role(), message.status(),
                message.content(), message.provider(), message.modelName(),
                message.inputTokens(), message.outputTokens(),
                message.latencyMs(), message.createdAt());
    }
}
