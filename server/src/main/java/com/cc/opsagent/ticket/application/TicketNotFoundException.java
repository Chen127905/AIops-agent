package com.cc.opsagent.ticket.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public final class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(long ticketId) {
        super("Ticket %d was not found".formatted(ticketId));
    }
}
