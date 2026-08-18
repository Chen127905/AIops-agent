package com.cc.opsagent.agent.application;

public interface AgentWorkflowEngine {

    TaskOutcome execute(AgentTaskCommand command);

    default TaskOutcome resume(
            AgentTaskCommand command,
            RecoveryCheckpoint checkpoint) {
        return execute(command);
    }
}
