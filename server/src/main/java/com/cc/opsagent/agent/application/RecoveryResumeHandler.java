package com.cc.opsagent.agent.application;

public interface RecoveryResumeHandler {

    void dispatch(long taskId, RecoveryCheckpoint checkpoint);
}
