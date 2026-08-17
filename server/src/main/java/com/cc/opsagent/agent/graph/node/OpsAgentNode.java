package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;

public interface OpsAgentNode {

    OpsAgentState apply(OpsAgentState state);
}
