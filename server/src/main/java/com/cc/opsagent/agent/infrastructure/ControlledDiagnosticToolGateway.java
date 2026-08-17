package com.cc.opsagent.agent.infrastructure;

import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.tool.application.OpsToolFacade;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ControlledDiagnosticToolGateway implements DiagnosticToolGateway {

    private final OpsToolFacade tools;

    public ControlledDiagnosticToolGateway(OpsToolFacade tools) {
        this.tools = tools;
    }

    @Override
    public ToolObservation execute(
            long tenantId,
            long taskId,
            String scenarioKey,
            String service,
            String toolName) {
        OpsContext context = new OpsContext(tenantId, taskId, scenarioKey);
        ToolResult<?> result = switch (toolName) {
            case "getServiceHealth" -> tools.getServiceHealth(context, service);
            case "queryMetrics" -> tools.queryMetrics(context, service, null, 120);
            case "queryLogs" -> tools.queryLogs(context, service, null, 100);
            case "getServiceDependencies" -> tools.getServiceDependencies(context, service);
            default -> throw new IllegalArgumentException(
                    "unsupported read-only diagnostic tool: " + toolName);
        };
        boolean success = result.status() == ToolExecutionStatus.SUCCESS;
        Map<String, Object> data = result.data() == null
                ? Map.of("message", result.message())
                : Map.of("result", result.data(), "truncated", result.truncated());
        return new ToolObservation(
                toolName, success, data, success ? null : result.message());
    }
}
