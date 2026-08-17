package com.cc.opsagent.model;

public record ModelReply(
        ModelProvider provider,
        String model,
        String content,
        ModelUsage usage) {
}
