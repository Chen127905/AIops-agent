package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.agent.graph.OpsAgentState;

import java.util.Set;

public class VerifyNode implements OpsAgentNode {

    private static final Set<String> HIGH_RISK = Set.of("restartService", "changeConfig");

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        if (HIGH_RISK.contains(state.proposedAction())) {
            state.verification(AgentTaskStatus.WAITING_APPROVAL);
        } else if ("NONE".equalsIgnoreCase(state.proposedAction())) {
            state.verification(AgentTaskStatus.MANUAL_REQUIRED);
        } else {
            state.verification(AgentTaskStatus.MANUAL_REQUIRED);
        }
        return state;
    }
}
