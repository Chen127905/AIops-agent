package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;

public class SummarizeNode implements OpsAgentNode {

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        Summary summary = new Summary(
                state.rootCause(),
                state.proposedAction(),
                state.confidence(),
                state.evidence().stream().map(chunk -> chunk.citationId()).toList());
        state.report("""
                诊断结论：%s
                根因：%s
                建议动作：%s
                置信度：%.0f%%
                处置步骤：%s
                验证标准：%s
                回滚方案：%s
                证据引用：%s
                """.formatted(
                text(state.diagnosisSummary(), summary.rootCause()),
                summary.rootCause(), summary.proposedAction(),
                summary.confidence() * 100,
                list(state.remediationSteps()), list(state.verificationSteps()),
                text(state.rollbackPlan(), "执行前确认原配置或实例状态，失败时恢复原值并转人工处理。"),
                summary.citations()));
        state.completeVerification();
        return state;
    }

    public record Summary(
            String rootCause,
            String proposedAction,
            double confidence,
            java.util.List<String> citations) { }

    private String list(java.util.List<String> values) {
        return values == null || values.isEmpty()
                ? "请根据诊断证据由值班人员确认处置步骤。"
                : String.join("；", values);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
