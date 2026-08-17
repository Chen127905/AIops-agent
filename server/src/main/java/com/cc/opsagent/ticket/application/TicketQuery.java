package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketStatus;

public record TicketQuery(TicketStatus status, int page, int size) {

    public TicketQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }

    public int offset() {
        return Math.multiplyExact(page - 1, size);
    }
}
