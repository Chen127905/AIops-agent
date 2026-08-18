package com.cc.opsagent.agent.infrastructure;

import java.time.Instant;

public record RecoveryCandidate(
        long tenantId,
        long taskId,
        long requestedBy,
        Instant leaseUntil) {
}
