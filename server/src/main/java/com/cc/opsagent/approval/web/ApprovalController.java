package com.cc.opsagent.approval.web;

import com.cc.opsagent.approval.application.ApprovalDecisionException;
import com.cc.opsagent.approval.application.ApprovalService;
import com.cc.opsagent.approval.application.ResumeCommand;
import com.cc.opsagent.approval.domain.ApprovalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<ApprovalRequest> pending() {
        return approvalService.pending();
    }

    @GetMapping("/{approvalId}")
    public ApprovalRequest get(@PathVariable long approvalId) {
        return approvalService.get(approvalId);
    }

    @PostMapping("/{approvalId}/approve")
    public ResumeCommand approve(
            @PathVariable long approvalId,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return approvalService.approve(
                approvalId, request == null ? null : request.comment());
    }

    @PostMapping("/{approvalId}/reject")
    public ApprovalRequest reject(
            @PathVariable long approvalId,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return approvalService.reject(
                approvalId, request == null ? null : request.comment());
    }

    @ExceptionHandler(ApprovalDecisionException.class)
    ResponseEntity<Void> decisionConflict(ApprovalDecisionException exception) {
        HttpStatus status = exception.getMessage().contains("not found")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).build();
    }

    public record DecisionRequest(@Size(max = 512) String comment) { }
}
