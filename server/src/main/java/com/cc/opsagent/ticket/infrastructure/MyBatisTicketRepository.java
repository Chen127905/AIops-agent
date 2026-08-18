package com.cc.opsagent.ticket.infrastructure;

import com.cc.opsagent.ticket.application.TicketRepository;
import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketStatus;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisTicketRepository implements TicketRepository {

    private final TicketMapper ticketMapper;

    public MyBatisTicketRepository(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    @Override
    public int insert(Ticket ticket) {
        return ticketMapper.insert(ticket);
    }

    @Override
    public Ticket findByTenantIdAndId(long tenantId, long ticketId) {
        return ticketMapper.selectByTenantIdAndId(tenantId, ticketId);
    }

    @Override
    public List<Ticket> findPageByTenantId(
            long tenantId,
            TicketStatus status,
            int offset,
            int size) {
        return ticketMapper.selectPageByTenantId(tenantId, status, offset, size);
    }

    @Override
    public long countByTenantId(long tenantId, TicketStatus status) {
        return ticketMapper.countByTenantId(tenantId, status);
    }

    @Override
    public int transitionStatus(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            TicketStatus targetStatus) {
        return ticketMapper.transitionStatus(
                tenantId, ticketId, expectedStatus, targetStatus);
    }

    @Override
    public int transitionStatusWithResolution(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            TicketStatus targetStatus,
            String resolutionSummary) {
        return ticketMapper.transitionStatusWithResolution(
                tenantId, ticketId, expectedStatus, targetStatus,
                resolutionSummary);
    }
}
