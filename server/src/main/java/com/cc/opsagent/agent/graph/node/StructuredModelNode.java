package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelReply;
import com.cc.opsagent.model.ModelRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

abstract class StructuredModelNode {

    private final ModelGateway model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    StructuredModelNode(ModelGateway model) {
        this.model = model;
    }

    protected <T> T callStructured(
            OpsAgentState state,
            String prompt,
            Class<T> type) {
        ModelReply reply = call(state, prompt);
        try {
            return objectMapper.readValue(reply.content(), type);
        } catch (JsonProcessingException firstFailure) {
            ModelReply repaired = call(state, """
                    Repair the following response into strict JSON only. Do not add prose.
                    Expected Java record type: %s
                    Invalid response:
                    %s
                    """.formatted(type.getSimpleName(), reply.content()));
            try {
                return objectMapper.readValue(repaired.content(), type);
            } catch (JsonProcessingException secondFailure) {
                throw new IllegalArgumentException(
                        "model returned malformed structured output", secondFailure);
            }
        }
    }

    private ModelReply call(OpsAgentState state, String prompt) {
        ModelReply reply = model.call(
                state.command().provider(),
                new ModelRequest(prompt, Map.of("taskId", state.command().taskId())));
        if (reply == null || reply.content() == null || reply.content().isBlank()) {
            throw new IllegalStateException("model returned an empty response");
        }
        if (reply.usage() != null) {
            state.addTokens(reply.usage().totalTokens());
        }
        return reply;
    }
}
