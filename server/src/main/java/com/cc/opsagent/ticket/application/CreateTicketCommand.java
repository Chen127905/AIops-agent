package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketSeverity;

public record CreateTicketCommand(
        String title,
        String description,
        String affectedService,
        String category,
        String scenarioKey,
        TicketSeverity severity) {
}
