package com.cc.opsagent.tool.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.tool.domain.ToolDecision;
import com.cc.opsagent.tool.domain.ToolDescriptor;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.tool.domain.ToolRisk;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ToolPolicyService {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private final Map<String, ToolDescriptor> descriptors;
    private final SecurityAuditPort audit;

    public ToolPolicyService() {
        this(SecurityAuditPort.noop());
    }

    @Autowired
    public ToolPolicyService(SecurityAuditPort audit) {
        this.audit = audit;
        this.descriptors = List.of(
                        descriptor("getServiceHealth", ToolRisk.READ_ONLY, false, 1, 0),
                        descriptor("queryMetrics", ToolRisk.READ_ONLY, false, 500, 0),
                        descriptor("queryLogs", ToolRisk.READ_ONLY, false, 200, 32 * 1024),
                        descriptor("getServiceDependencies", ToolRisk.READ_ONLY, false, 100, 0),
                        descriptor("searchRunbook", ToolRisk.READ_ONLY, false, 20, 0),
                        descriptor("restartService", ToolRisk.HIGH_RISK, true, 1, 0),
                        descriptor("changeConfig", ToolRisk.HIGH_RISK, true, 1, 0))
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        ToolDescriptor::name, Function.identity()));
    }

    public ToolDecision evaluate(ToolInvocationRequest request) {
        ToolDescriptor descriptor = descriptors.get(request.toolName());
        if (descriptor == null) {
            return reject(request, "Tool is not present in the allowlist", null);
        }
        if (request.tenantId() != TenantContext.requireTenantId()) {
            return reject(
                    request,
                    "Tool request tenant does not match authentication",
                    descriptor);
        }
        boolean approvalSatisfied = !descriptor.requiresApproval()
                || hasText(request.approvedRequestId());
        if (approvalSatisfied
                && descriptor.risk() == ToolRisk.HIGH_RISK
                && !hasText(request.idempotencyKey())) {
            return reject(
                    request,
                    "Approved high-risk tools require an idempotency key", descriptor);
        }
        ToolDecision decision = new ToolDecision(
                true,
                descriptor.risk(),
                descriptor.requiresApproval(),
                approvalSatisfied,
                approvalSatisfied ? "Allowed" : "Approval is required",
                descriptor);
        record(request,
                approvalSatisfied ? "TOOL_POLICY_ALLOWED"
                        : "TOOL_APPROVAL_REQUIRED",
                approvalSatisfied ? "SUCCEEDED" : "REQUESTED",
                decision.reason(), descriptor);
        return decision;
    }

    public List<ToolDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    private ToolDecision rejected(String reason, ToolDescriptor descriptor) {
        return new ToolDecision(
                false,
                descriptor == null ? null : descriptor.risk(),
                descriptor != null && descriptor.requiresApproval(),
                false,
                reason,
                descriptor);
    }

    private ToolDecision reject(
            ToolInvocationRequest request,
            String reason,
            ToolDescriptor descriptor) {
        record(request, "TOOL_POLICY_REJECTED", "REJECTED", reason, descriptor);
        return rejected(reason, descriptor);
    }

    private void record(
            ToolInvocationRequest request,
            String eventType,
            String outcome,
            String reason,
            ToolDescriptor descriptor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", request.taskId());
        details.put("requestTenantId", request.tenantId());
        details.put("reason", reason);
        if (descriptor != null) details.put("risk", descriptor.risk().name());
        audit.record(new SecurityAuditEvent(
                TenantContext.requireTenantId(), TenantContext.requireUserId(),
                eventType, outcome, "TOOL", request.toolName(), details));
    }

    private static ToolDescriptor descriptor(
            String name,
            ToolRisk risk,
            boolean requiresApproval,
            int maxItems,
            int maxBytes) {
        return new ToolDescriptor(
                name, risk, requiresApproval, TOOL_TIMEOUT, maxItems, maxBytes);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
