package com.cc.opsagent.approval.application;

import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.ToolInvocationRecord;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.approval.domain.ApprovalStatus;
import com.cc.opsagent.approval.infrastructure.ApprovalRepository;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider.HealthSnapshot;
import com.cc.opsagent.simulator.domain.ScenarioState;
import com.cc.opsagent.tool.application.OpsToolFacade;
import com.cc.opsagent.tool.application.ToolPolicyService;
import com.cc.opsagent.tool.domain.ToolDecision;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.tool.domain.ToolResult;
import com.cc.opsagent.tool.domain.ToolRisk;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ApprovalResumeService {

    private final ApprovalRepository repository;
    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final ToolPolicyService policy;
    private final OpsToolFacade tools;

    public ApprovalResumeService(
            ApprovalRepository repository,
            AgentTaskService taskService,
            AgentEventService eventService,
            ToolPolicyService policy,
            OpsToolFacade tools) {
        this.repository = repository;
        this.taskService = taskService;
        this.eventService = eventService;
        this.policy = policy;
        this.tools = tools;
    }

    public void resume(ResumeCommand command) {
        if (TenantContext.requireTenantId() != command.tenantId()) {
            throw new IllegalStateException("approval resume tenant mismatch");
        }
        if (repository.claimExecution(
                command.tenantId(), command.approvalId()) != 1) {
            return;
        }
        long started = System.nanoTime();
        try {
            ToolInvocationRequest request = new ToolInvocationRequest(
                    command.tenantId(), command.taskId(), command.scenarioKey(),
                    command.toolName(), command.normalizedArguments(),
                    Long.toString(command.approvalId()), command.idempotencyKey());
            ToolDecision decision = policy.evaluate(request);
            if (!decision.allowed() || !decision.approvalSatisfied()) {
                fail(command, "tool policy rejected approved resume: " + decision.reason());
                return;
            }
            if (!taskService.transition(
                    command.taskId(), AgentTaskStatus.WAITING_APPROVAL,
                    AgentTaskStatus.RUNNING)) {
                fail(command, "task is no longer waiting for approval");
                return;
            }
            eventService.append(command.taskId(), "APPROVAL_EXECUTION_STARTED", Map.of(
                    "approvalId", command.approvalId(),
                    "tool", command.toolName()));
            ToolResult<?> result = execute(command);
            taskService.appendToolInvocation(new ToolInvocationRecord(
                    command.taskId(), null, command.toolName(),
                    command.normalizedArguments(), decision.risk(), result.status(),
                    command.idempotencyKey(), elapsed(started),
                    resultSummary(result), result.message()));
            if (result.status() == ToolExecutionStatus.SUCCESS) {
                if (!verifyRecovery(command)) {
                    failRunning(command,
                            "post-action verification did not confirm service recovery");
                    return;
                }
                if (!taskService.transition(
                        command.taskId(), AgentTaskStatus.RUNNING,
                        AgentTaskStatus.SUCCEEDED)) {
                    throw new IllegalStateException(
                            "task state changed before approved execution completed");
                }
                eventService.append(command.taskId(), "APPROVAL_EXECUTION_COMPLETED", Map.of(
                        "approvalId", command.approvalId(),
                        "status", "SUCCEEDED"));
                eventService.append(command.taskId(), "TASK_COMPLETED", Map.of(
                        "status", "SUCCEEDED"));
                if (repository.finishExecution(
                        command.tenantId(), command.approvalId(),
                        ApprovalStatus.EXECUTED, null) != 1) {
                    throw new IllegalStateException(
                            "approval state changed before execution completed");
                }
            } else {
                failRunning(command, result.message());
            }
        } catch (RuntimeException exception) {
            failRunning(command, safeError(exception.getMessage()));
        }
    }

    private ToolResult<?> execute(ResumeCommand command) {
        String service = requiredString(command.normalizedArguments(), "service");
        OpsContext context = new OpsContext(
                command.tenantId(), command.taskId(), command.scenarioKey());
        return switch (command.toolName()) {
            case "restartService" -> tools.restartService(
                    context, service, Long.toString(command.approvalId()),
                    command.idempotencyKey());
            case "changeConfig" -> tools.changeConfig(
                    context, service, changes(command.normalizedArguments()),
                    Long.toString(command.approvalId()), command.idempotencyKey());
            default -> throw new IllegalArgumentException(
                    "unsupported approved tool " + command.toolName());
        };
    }

    private boolean verifyRecovery(ResumeCommand command) {
        String service = requiredString(command.normalizedArguments(), "service");
        OpsContext context = new OpsContext(
                command.tenantId(), command.taskId(), command.scenarioKey());
        long started = System.nanoTime();
        ToolResult<HealthSnapshot> verification =
                tools.getServiceHealth(context, service);
        HealthSnapshot health = verification.data();
        boolean recovered = verification.status() == ToolExecutionStatus.SUCCESS
                && health != null
                && health.scenarioState() == ScenarioState.RECOVERED
                && "UP".equalsIgnoreCase(health.status());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", verification.status().name());
        summary.put("recovered", recovered);
        if (health != null) {
            summary.put("serviceStatus", health.status());
            summary.put("scenarioState", health.scenarioState().name());
        }
        taskService.appendToolInvocation(new ToolInvocationRecord(
                command.taskId(), null, "getServiceHealth",
                Map.of("service", service, "phase", "post-action"),
                ToolRisk.READ_ONLY, verification.status(), null,
                elapsed(started), Map.copyOf(summary), verification.message()));
        eventService.append(command.taskId(), "POST_ACTION_VERIFIED", Map.of(
                "approvalId", command.approvalId(),
                "service", service,
                "recovered", recovered));
        return recovered;
    }

    private Map<String, String> changes(Map<String, Object> arguments) {
        Object value = arguments.get("changes");
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            throw new IllegalArgumentException("changeConfig requires non-empty changes");
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    private String requiredString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text.trim();
    }

    private Map<String, Object> resultSummary(ToolResult<?> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.status().name());
        summary.put("changed", result.status() == ToolExecutionStatus.SUCCESS);
        return Map.copyOf(summary);
    }

    private void fail(ResumeCommand command, String error) {
        String safe = safeError(error);
        taskService.transition(
                command.taskId(), AgentTaskStatus.WAITING_APPROVAL,
                AgentTaskStatus.MANUAL_REQUIRED);
        taskService.recordErrorSummary(command.taskId(), safe);
        repository.finishExecution(
                command.tenantId(), command.approvalId(),
                ApprovalStatus.FAILED, safe);
        eventService.append(command.taskId(), "APPROVAL_EXECUTION_FAILED", Map.of(
                "approvalId", command.approvalId(),
                "error", safe));
    }

    private void failRunning(ResumeCommand command, String error) {
        String safe = safeError(error);
        AgentTaskStatus status = taskService.get(command.taskId()).status();
        if (status == AgentTaskStatus.RUNNING) {
            taskService.transition(
                    command.taskId(), AgentTaskStatus.RUNNING,
                    AgentTaskStatus.FAILED);
        } else if (status == AgentTaskStatus.WAITING_APPROVAL) {
            taskService.transition(
                    command.taskId(), AgentTaskStatus.WAITING_APPROVAL,
                    AgentTaskStatus.MANUAL_REQUIRED);
        }
        taskService.recordErrorSummary(command.taskId(), safe);
        repository.finishExecution(
                command.tenantId(), command.approvalId(),
                ApprovalStatus.FAILED, safe);
        eventService.append(command.taskId(), "APPROVAL_EXECUTION_FAILED", Map.of(
                "approvalId", command.approvalId(),
                "error", safe));
    }

    private String safeError(String value) {
        if (value == null || value.isBlank()) return "approved tool execution failed";
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
