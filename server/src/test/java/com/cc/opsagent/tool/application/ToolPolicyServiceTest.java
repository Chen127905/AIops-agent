package com.cc.opsagent.tool.application;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.tool.domain.ToolRisk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyServiceTest {

    private final ToolPolicyService policy = new ToolPolicyService();

    @BeforeEach
    void authenticateTenant() {
        TenantPrincipal principal = new TenantPrincipal(
                1L, 10L, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiresApprovalForRestart() {
        var decision = policy.evaluate(request(
                1L, "restartService", Map.of("service", "order-service"), null));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.risk()).isEqualTo(ToolRisk.HIGH_RISK);
        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.approvalSatisfied()).isFalse();
    }

    @Test
    void recognizesAnAttachedApprovalWithoutChangingTheRisk() {
        var decision = policy.evaluate(request(
                1L, "changeConfig", Map.of("service", "payment-api"), "approval-1"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.risk()).isEqualTo(ToolRisk.HIGH_RISK);
        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.approvalSatisfied()).isTrue();
    }

    @Test
    void rejectsApprovedHighRiskToolWithoutAnIdempotencyKey() {
        ToolInvocationRequest request = new ToolInvocationRequest(
                1L,
                100L,
                "redis-timeout",
                "restartService",
                Map.of("service", "order-service"),
                "approval-1",
                null);

        var decision = policy.evaluate(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("idempotency");
    }

    @Test
    void forbidsUnknownTool() {
        var decision = policy.evaluate(request(1L, "executeShell", Map.of(), null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("allowlist");
    }

    @Test
    void rejectsARequestForAnotherTenant() {
        var decision = policy.evaluate(request(
                2L, "getServiceHealth", Map.of("service", "order-service"), null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("tenant");
    }

    @Test
    void registersFiveReadOnlyAndTwoHighRiskToolsWithFiveSecondTimeouts() {
        assertThat(policy.descriptors()).hasSize(7);
        assertThat(policy.descriptors())
                .allSatisfy(descriptor ->
                        assertThat(descriptor.timeout()).hasSeconds(5));
        assertThat(policy.descriptors())
                .filteredOn(descriptor -> descriptor.risk() == ToolRisk.READ_ONLY)
                .hasSize(5);
        assertThat(policy.descriptors())
                .filteredOn(descriptor -> descriptor.risk() == ToolRisk.HIGH_RISK)
                .hasSize(2);
    }

    private ToolInvocationRequest request(
            long tenantId,
            String toolName,
            Map<String, Object> arguments,
            String approvedRequestId) {
        return new ToolInvocationRequest(
                tenantId,
                100L,
                "redis-timeout",
                toolName,
                arguments,
                approvedRequestId,
                "idempotency-1");
    }
}
