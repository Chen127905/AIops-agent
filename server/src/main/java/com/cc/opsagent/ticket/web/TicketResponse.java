package com.cc.opsagent.ticket.web;

import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import com.cc.opsagent.ticket.domain.TicketStatus;

import java.time.LocalDateTime;

public record TicketResponse(
        long id,
        long tenantId,
        long reporterId,
        String title,
        String description,
        String affectedService,
        String category,
        String scenarioKey,
        TicketSeverity severity,
        TicketStatus status,
        String resolutionSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTenantId(),
                ticket.getReporterId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getAffectedService(),
                ticket.getCategory(),
                ticket.getScenarioKey(),
                ticket.getSeverity(),
                ticket.getStatus(),
                ticket.getResolutionSummary(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }
}
