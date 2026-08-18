package com.cc.opsagent.agent.application;

@FunctionalInterface
public interface CancellationProbe {

    boolean requested(long taskId);

    static CancellationProbe never() {
        return taskId -> false;
    }
}
