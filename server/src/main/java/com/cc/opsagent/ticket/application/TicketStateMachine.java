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

    private static final Map<TicketStatus, Set<TicketStatus>> NORMAL_TRANSITIONS = Map.of(
            TicketStatus.OPEN, Set.of(TicketStatus.TRIAGING),
            TicketStatus.TRIAGING, Set.of(TicketStatus.DIAGNOSING),
            TicketStatus.DIAGNOSING, Set.of(
                    TicketStatus.WAITING_APPROVAL, TicketStatus.VERIFYING),
            TicketStatus.WAITING_APPROVAL, Set.of(TicketStatus.EXECUTING),
            TicketStatus.EXECUTING, Set.of(TicketStatus.VERIFYING),
            TicketStatus.VERIFYING, Set.of(TicketStatus.RESOLVED));

    public boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null || from == to || from.isTerminal()) {
            return false;
        }
        return NORMAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)
                || EXCEPTIONAL_TERMINALS.contains(to);
    }
}
