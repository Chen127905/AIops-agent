package com.cc.opsagent.agent.application;

public interface DiagnosticToolGateway {

    ToolObservation execute(
            long tenantId,
            long taskId,
            String scenarioKey,
            String service,
            String toolName);
}
