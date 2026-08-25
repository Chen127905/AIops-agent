package com.cc.opsagent.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendConversationMessageRequest(
        @NotBlank @Size(max = 4_000) String content) {
}
