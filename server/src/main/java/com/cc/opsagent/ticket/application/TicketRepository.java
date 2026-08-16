package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketStatus;

public interface TicketRepository {

    int insert(Ticket ticket);

    Ticket findByTenantIdAndId(long tenantId, long ticketId);

    int transitionStatus(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            TicketStatus targetStatus);
}
