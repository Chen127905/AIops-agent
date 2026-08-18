package com.cc.opsagent.agent.application;

public enum RecoveryDecision {
    RESUME_ENQUEUED,
    CANCELLED,
    COMPLETED_FROM_IDEMPOTENCY_RECORD,
    MANUAL_REQUIRED,
    NOT_ELIGIBLE
}
