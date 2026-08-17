package com.cc.opsagent.ticket.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class TicketConflictException extends RuntimeException {

    public TicketConflictException(String message) {
        super(message);
    }
}
