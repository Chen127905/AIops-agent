package com.cc.opsagent.agent.graph;

import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.knowledge.application.EvidenceChunk;

import java.util.ArrayList;
import java.util.List;

public final class OpsAgentState {

    private final AgentTaskCommand command;
    private int steps;
    private int tokens;
    private String category;
    private String urgency;
    private List<EvidenceChunk> evidence = List.of();
    private List<String> plannedTools = List.of();
    private final List<ToolObservation> observations = new ArrayList<>();
    private String rootCause;
    private String proposedAction;
    private double confidence;
    private AgentTaskStatus status = AgentTaskStatus.RUNNING;
    private String report;
    private String error;

    public OpsAgentState(AgentTaskCommand command) {
        this.command = command;
    }

    public boolean enterStep() {
        if (terminal()) {
            return false;
        }
        steps++;
        if (steps > command.budget().maxSteps()) {
            fail("agent step budget exceeded");
            return false;
        }
        return true;
    }

    public void addTokens(Integer count) {
        if (count != null) {
            tokens += count;
            if (tokens > command.budget().maxTokens()) {
                fail("agent token budget exceeded");
            }
        }
    }

    public boolean terminal() {
        return status.terminal() || status == AgentTaskStatus.WAITING_APPROVAL;
    }

    public void fail(String message) {
        status = AgentTaskStatus.FAILED;
        error = message;
    }

    public TaskOutcome outcome() {
        return new TaskOutcome(
                status,
                rootCause,
                observations.stream().map(ToolObservation::toolName).toList(),
                evidence.stream().map(EvidenceChunk::citationId).toList(),
                proposedAction,
                report,
                error);
    }

    public AgentTaskCommand command() { return command; }
    public String category() { return category; }
    public void triage(String value, String severity) { category = value; urgency = severity; }
    public String urgency() { return urgency; }
    public List<EvidenceChunk> evidence() { return evidence; }
    public void evidence(List<EvidenceChunk> value) { evidence = List.copyOf(value); }
    public List<String> plannedTools() { return plannedTools; }
    public void plannedTools(List<String> value) { plannedTools = List.copyOf(value); }
    public List<ToolObservation> observations() { return observations; }
    public void observation(ToolObservation value) { observations.add(value); }
    public String rootCause() { return rootCause; }
    public String proposedAction() { return proposedAction; }
    public double confidence() { return confidence; }
    public void decision(String cause, String action, double score) {
        rootCause = cause; proposedAction = action; confidence = score;
    }
    public AgentTaskStatus status() { return status; }
    public void status(AgentTaskStatus value) { status = value; }
    public void report(String value) { report = value; }
}
