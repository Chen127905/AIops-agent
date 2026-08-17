package com.cc.opsagent.agent.application;

public class ActiveTaskExistsException extends IllegalStateException {

    public ActiveTaskExistsException(long ticketId) {
        super("an active agent task already exists for ticket " + ticketId);
    }
}
