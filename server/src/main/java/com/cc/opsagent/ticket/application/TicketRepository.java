package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketStatus;

import java.util.List;

public interface TicketRepository {

    int insert(Ticket ticket);

    Ticket findByTenantIdAndId(long tenantId, long ticketId);

    List<Ticket> findPageByTenantId(
            long tenantId,
            TicketStatus status,
            int offset,
            int size);

    long countByTenantId(long tenantId, TicketStatus status);

    int transitionStatus(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            TicketStatus targetStatus);

    int transitionStatusWithResolution(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            TicketStatus targetStatus,
            String resolutionSummary);
}
