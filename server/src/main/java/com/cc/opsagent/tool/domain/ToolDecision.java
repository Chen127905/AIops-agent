package com.cc.opsagent.tool.domain;

public record ToolDecision(
        boolean allowed,
        ToolRisk risk,
        boolean requiresApproval,
        boolean approvalSatisfied,
        String reason,
        ToolDescriptor descriptor) {
}
