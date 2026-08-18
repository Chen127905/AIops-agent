package com.cc.opsagent.ticket.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import com.cc.opsagent.ticket.domain.TicketStatus;
import com.cc.opsagent.ticket.web.TicketResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketStateMachine stateMachine;
    private final SecurityAuditPort audit;

    public TicketService(
            TicketRepository ticketRepository,
            TicketStateMachine stateMachine,
            SecurityAuditPort audit) {
        this.ticketRepository = ticketRepository;
        this.stateMachine = stateMachine;
        this.audit = audit;
    }

    @Transactional
    public TicketResponse create(CreateTicketCommand command) {
        long tenantId = TenantContext.requireTenantId();
        long reporterId = TenantContext.requireUserId();
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setReporterId(reporterId);
        ticket.setTitle(command.title());
        ticket.setDescription(command.description());
        ticket.setAffectedService(command.affectedService());
        ticket.setCategory(command.category());
        ticket.setScenarioKey(command.scenarioKey());
        ticket.setSeverity(command.severity() == null
                ? TicketSeverity.UNKNOWN
                : command.severity());
        ticket.setStatus(TicketStatus.OPEN);
        if (ticketRepository.insert(ticket) != 1) {
            throw new TicketConflictException("Ticket could not be created");
        }
        return TicketResponse.from(requireTicket(tenantId, ticket.getId()));
    }

    @Transactional(readOnly = true)
    public TicketResponse get(long id) {
        return TicketResponse.from(requireTicket(TenantContext.requireTenantId(), id));
    }

    @Transactional(readOnly = true)
    public PageResult<TicketResponse> list(TicketQuery query) {
        long tenantId = TenantContext.requireTenantId();
        List<TicketResponse> items = ticketRepository.findPageByTenantId(
                        tenantId, query.status(), query.offset(), query.size())
                .stream()
                .map(TicketResponse::from)
                .toList();
        long total = ticketRepository.countByTenantId(tenantId, query.status());
        return new PageResult<>(items, total, query.page(), query.size());
    }

    @Transactional
    public void cancel(long id) {
        transition(id, TicketStatus.CANCELLED);
    }

    @Transactional
    public TicketResponse transition(long id, TicketStatus targetStatus) {
        long tenantId = TenantContext.requireTenantId();
        Ticket current = requireTicket(tenantId, id);
        if (!stateMachine.canTransition(current.getStatus(), targetStatus)) {
            throw new TicketConflictException(
                    "Ticket cannot transition from %s to %s"
                            .formatted(current.getStatus(), targetStatus));
        }
        int updated = ticketRepository.transitionStatus(
                tenantId, id, current.getStatus(), targetStatus);
        if (updated != 1) {
            throw new TicketConflictException(
                    "Ticket state changed concurrently; reload before retrying");
        }
        return TicketResponse.from(requireTicket(tenantId, id));
    }

    @Transactional
    public TicketResponse resolve(long id, String resolutionSummary) {
        if (resolutionSummary == null || resolutionSummary.isBlank()) {
            throw new IllegalArgumentException("resolution summary is required");
        }
        long tenantId = TenantContext.requireTenantId();
        Ticket current = requireTicket(tenantId, id);
        if (!stateMachine.canTransition(current.getStatus(), TicketStatus.RESOLVED)) {
            throw new TicketConflictException(
                    "Ticket cannot resolve from " + current.getStatus());
        }
        int updated = ticketRepository.transitionStatusWithResolution(
                tenantId, id, current.getStatus(), TicketStatus.RESOLVED,
                resolutionSummary.trim());
        if (updated != 1) {
            throw new TicketConflictException(
                    "Ticket state changed concurrently; reload before retrying");
        }
        return TicketResponse.from(requireTicket(tenantId, id));
    }

    private Ticket requireTicket(long tenantId, long id) {
        Ticket ticket = ticketRepository.findByTenantIdAndId(tenantId, id);
        if (ticket == null) {
            audit.record(new SecurityAuditEvent(
                    tenantId, TenantContext.requireUserId(),
                    "TENANT_RESOURCE_ACCESS_REJECTED", "REJECTED",
                    "TICKET", Long.toString(id),
                    Map.of("reason", "NOT_OWNED_OR_NOT_FOUND")));
            throw new TicketNotFoundException(id);
        }
        return ticket;
    }
}
