package com.cc.opsagent.ticket.web;

import com.cc.opsagent.ticket.application.CreateTicketCommand;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
        @NotBlank @Size(min = 5, max = 120) String title,
        @NotBlank @Size(min = 10, max = 4000) String description,
        @Size(max = 128) String affectedService,
        @Size(max = 64) String category,
        TicketSeverity severity) {

    CreateTicketCommand toCommand() {
        return new CreateTicketCommand(
                title, description, affectedService, category, severity);
    }
}
