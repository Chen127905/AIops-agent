package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelReply;
import com.cc.opsagent.model.ModelRequest;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

abstract class StructuredModelNode {

    private final ModelGateway model;
    private final AgentExecutionAudit audit;
    private final CancellationProbe cancellation;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    StructuredModelNode(ModelGateway model) {
        this(model, AgentExecutionAudit.noop(), CancellationProbe.never(),
                new SensitiveDataRedactor());
    }

    StructuredModelNode(ModelGateway model, AgentExecutionAudit audit) {
        this(model, audit, CancellationProbe.never(),
                new SensitiveDataRedactor());
    }

    StructuredModelNode(
            ModelGateway model,
            AgentExecutionAudit audit,
            CancellationProbe cancellation) {
        this(model, audit, cancellation, new SensitiveDataRedactor());
    }

    StructuredModelNode(
            ModelGateway model,
            AgentExecutionAudit audit,
            CancellationProbe cancellation,
            SensitiveDataRedactor redactor) {
        this.model = model;
        this.audit = audit;
        this.cancellation = cancellation;
        this.redactor = redactor;
    }

    protected <T> T callStructured(
            OpsAgentState state,
            String prompt,
            Class<T> type) {
        ModelReply reply = call(state, prompt);
        try {
            return objectMapper.readValue(jsonContent(reply.content()), type);
        } catch (JsonProcessingException firstFailure) {
            ModelReply repaired = call(state, """
                    Repair the following response into strict JSON only. Do not add prose.
                    Expected Java record type: %s
                    Invalid response:
                    %s
                    """.formatted(type.getSimpleName(), reply.content()));
            try {
                return objectMapper.readValue(jsonContent(repaired.content()), type);
            } catch (JsonProcessingException secondFailure) {
                throw new IllegalArgumentException(
                        "model returned malformed structured output", secondFailure);
            }
        }
    }

    private String jsonContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                value = value.substring(firstLine + 1, closing).trim();
            }
        }
        int objectStart = value.indexOf('{');
        int objectEnd = value.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return value.substring(objectStart, objectEnd + 1);
        }
        return value;
    }

    private ModelReply call(OpsAgentState state, String prompt) {
        String safePrompt = redactor.redact(prompt);
        long started = System.nanoTime();
        ModelReply reply;
        try {
            reply = model.call(
                    state.command().provider(),
                    new ModelRequest(
                            safePrompt, Map.of("taskId", state.command().taskId())));
            if (reply == null || reply.content() == null || reply.content().isBlank()) {
                throw new IllegalStateException("model returned an empty response");
            }
        } catch (RuntimeException exception) {
            record(state, safePrompt, null, "FAILED", exception.getMessage(), started);
            throw exception;
        }
        record(state, safePrompt, reply, "SUCCEEDED", null, started);
        if (reply.usage() != null) {
            state.addTokens(reply.usage().totalTokens());
        }
        boolean cancelled = !state.terminal()
                && cancellation.requested(state.command().taskId());
        if (cancelled) {
            state.cancel();
        }
        if (cancelled) {
            throw new IllegalStateException("agent stopped after model invocation");
        }
        state.controlPoint();
        return reply;
    }

    private void record(
            OpsAgentState state,
            String prompt,
            ModelReply reply,
            String status,
            String error,
            long started) {
        int inputTokens = reply == null || reply.usage() == null
                || reply.usage().promptTokens() == null
                ? 0 : reply.usage().promptTokens();
        int outputTokens = reply == null || reply.usage() == null
                || reply.usage().completionTokens() == null
                ? 0 : reply.usage().completionTokens();
        audit.modelInvoked(new AgentExecutionAudit.ModelCallAudit(
                state.command().taskId(), state.command().provider().name(),
                reply == null || reply.model() == null || reply.model().isBlank()
                        ? "unknown" : reply.model(),
                sha256(prompt), status, inputTokens, outputTokens,
                Math.max(0, (System.nanoTime() - started) / 1_000_000), error));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
