package com.cc.opsagent.conversation.web;

import com.cc.opsagent.conversation.application.ConversationBusyException;
import com.cc.opsagent.conversation.application.ConversationReplyException;
import com.cc.opsagent.conversation.application.TicketConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets/{ticketId}/conversation")
public class TicketConversationController {

    private final TicketConversationService service;

    public TicketConversationController(TicketConversationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<TicketConversationResponse> get(
            @PathVariable long ticketId) {
        var conversation = service.get(ticketId);
        return conversation == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(TicketConversationResponse.from(conversation));
    }

    @PostMapping("/messages")
    public TicketConversationResponse send(
            @PathVariable long ticketId,
            @Valid @RequestBody SendConversationMessageRequest request) {
        return TicketConversationResponse.from(
                service.send(ticketId, request.content()));
    }

    @ExceptionHandler(ConversationBusyException.class)
    ResponseEntity<Void> busy() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(ConversationReplyException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
