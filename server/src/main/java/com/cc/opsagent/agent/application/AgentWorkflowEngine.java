package com.cc.opsagent.agent.application;

public interface AgentWorkflowEngine {

    TaskOutcome execute(AgentTaskCommand command);
}
