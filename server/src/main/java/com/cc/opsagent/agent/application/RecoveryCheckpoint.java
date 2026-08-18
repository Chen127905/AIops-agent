package com.cc.opsagent.agent.application;

import java.util.Map;

public record RecoveryCheckpoint(
        String lastCompletedNode,
        int completedSequence,
        Map<String, Object> state) {

    public RecoveryCheckpoint {
        if (lastCompletedNode == null || lastCompletedNode.isBlank()) {
            throw new IllegalArgumentException("checkpoint node must not be blank");
        }
        if (completedSequence <= 0) {
            throw new IllegalArgumentException("checkpoint sequence must be positive");
        }
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
