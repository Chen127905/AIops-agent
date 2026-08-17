package com.cc.opsagent.tool.domain;

public record ToolResult<T>(
        ToolExecutionStatus status,
        T data,
        String message,
        ToolRisk risk,
        boolean truncated) {

    public static <T> ToolResult<T> success(
            T data,
            ToolRisk risk,
            boolean truncated) {
        return new ToolResult<>(
                ToolExecutionStatus.SUCCESS, data, "OK", risk, truncated);
    }

    public static <T> ToolResult<T> withoutData(
            ToolExecutionStatus status,
            String message,
            ToolRisk risk) {
        return new ToolResult<>(status, null, message, risk, false);
    }
}
