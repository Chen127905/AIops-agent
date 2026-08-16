package com.cc.opsagent.ticket.domain;

public enum TicketStatus {
    OPEN(false),
    TRIAGING(false),
    DIAGNOSING(false),
    WAITING_APPROVAL(false),
    EXECUTING(false),
    VERIFYING(false),
    RESOLVED(true),
    FAILED(true),
    CANCELLED(true),
    TIMEOUT(true),
    MANUAL_REQUIRED(true);

    private final boolean terminal;

    TicketStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
