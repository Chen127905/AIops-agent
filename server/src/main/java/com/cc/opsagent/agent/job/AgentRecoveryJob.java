package com.cc.opsagent.agent.job;

import com.cc.opsagent.agent.application.AgentRecoveryService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.infrastructure.RecoveryCandidate;
import com.cc.opsagent.identity.security.TenantPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.agent.recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentRecoveryJob {

    private final AgentTaskService tasks;
    private final AgentRecoveryService recovery;

    public AgentRecoveryJob(
            AgentTaskService tasks,
            AgentRecoveryService recovery) {
        this.tasks = tasks;
        this.recovery = recovery;
    }

    @Scheduled(
            fixedDelayString = "${app.agent.recovery.interval:PT30S}",
            initialDelayString = "${app.agent.recovery.initial-delay:PT1M}")
    public void recoverExpiredTasks() {
        Instant now = Instant.now();
        tasks.expireApprovalWaits(now);
        for (RecoveryCandidate candidate : tasks.expiredRunning(now, 100)) {
            try {
                authenticate(candidate);
                recovery.recover(candidate.taskId(), now);
            } catch (RuntimeException ignored) {
                // The next scan retries still-eligible tasks; one task must not stop the batch.
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private void authenticate(RecoveryCandidate candidate) {
        TenantPrincipal principal = new TenantPrincipal(
                candidate.tenantId(), candidate.requestedBy(),
                "agent-recovery", Set.of("SYSTEM"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
    }
}
