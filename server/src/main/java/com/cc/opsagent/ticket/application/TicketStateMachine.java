package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public final class TicketStateMachine {

    private static final Set<TicketStatus> EXCEPTIONAL_TERMINALS = EnumSet.of(
            TicketStatus.FAILED,
            TicketStatus.CANCELLED,
            TicketStatus.TIMEOUT,
            TicketStatus.MANUAL_REQUIRED);

    private static final Map<TicketStatus, TicketStatus> NORMAL_TRANSITIONS = Map.of(
            TicketStatus.OPEN, TicketStatus.TRIAGING,
            TicketStatus.TRIAGING, TicketStatus.DIAGNOSING,
            TicketStatus.DIAGNOSING, TicketStatus.WAITING_APPROVAL,
            TicketStatus.WAITING_APPROVAL, TicketStatus.EXECUTING,
            TicketStatus.EXECUTING, TicketStatus.VERIFYING,
            TicketStatus.VERIFYING, TicketStatus.RESOLVED);

    public boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null || from == to || from.isTerminal()) {
            return false;
        }
        return NORMAL_TRANSITIONS.get(from) == to || EXCEPTIONAL_TERMINALS.contains(to);
    }
}
