package com.cc.opsagent.agent.domain;

public enum AgentTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    MANUAL_REQUIRED;

    public boolean terminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, MANUAL_REQUIRED -> true;
            default -> false;
        };
    }
}
