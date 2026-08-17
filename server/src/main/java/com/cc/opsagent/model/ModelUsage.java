package com.cc.opsagent.model;

public record ModelUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    public static ModelUsage unavailable() {
        return new ModelUsage(null, null, null);
    }
}
