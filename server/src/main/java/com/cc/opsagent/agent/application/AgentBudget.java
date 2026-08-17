package com.cc.opsagent.agent.application;

import java.time.Duration;

public record AgentBudget(int maxSteps, Duration timeout, int maxTokens) {

    public AgentBudget {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("max steps must be positive");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("timeout must be at least one second");
        }
        if (timeout.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout is too large");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("max tokens must be positive");
        }
    }
}
