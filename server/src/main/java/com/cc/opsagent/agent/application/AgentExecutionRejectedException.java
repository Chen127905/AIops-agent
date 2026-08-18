package com.cc.opsagent.agent.application;

public class AgentExecutionRejectedException extends RuntimeException {

    public AgentExecutionRejectedException() {
        super("agent execution capacity is exhausted");
    }
}
