package com.cc.opsagent.agent.graph;

import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.knowledge.application.EvidenceChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpsAgentState {

    private final AgentTaskCommand command;
    private final long startedAtNanos;
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
    private AgentTaskStatus verifiedStatus;
    private String report;
    private String error;

    public OpsAgentState(AgentTaskCommand command) {
        this.command = command;
        this.startedAtNanos = System.nanoTime();
    }

    public boolean enterStep() {
        if (terminal()) {
            return false;
        }
        if (timedOut()) {
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
        timedOut();
    }

    public boolean terminal() {
        return status.terminal() || status == AgentTaskStatus.WAITING_APPROVAL;
    }

    public void fail(String message) {
        if (status == AgentTaskStatus.TIMED_OUT) {
            return;
        }
        status = AgentTaskStatus.FAILED;
        error = message;
    }

    private boolean timedOut() {
        if (System.nanoTime() - startedAtNanos
                >= command.budget().timeout().toNanos()) {
            status = AgentTaskStatus.TIMED_OUT;
            error = "agent timeout budget exceeded";
            return true;
        }
        return false;
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

    public int nextStepSequence() { return steps + 1; }

    public Map<String, Object> auditSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status.name());
        snapshot.put("steps", steps);
        snapshot.put("tokens", tokens);
        if (category != null) snapshot.put("category", category);
        if (urgency != null) snapshot.put("urgency", urgency);
        if (!evidence.isEmpty()) {
            snapshot.put("citations", evidence.stream()
                    .map(EvidenceChunk::citationId).toList());
        }
        if (!plannedTools.isEmpty()) snapshot.put("plannedTools", plannedTools);
        if (!observations.isEmpty()) {
            snapshot.put("observedTools", observations.stream()
                    .map(ToolObservation::toolName).toList());
        }
        if (rootCause != null) snapshot.put("rootCause", rootCause);
        if (proposedAction != null) snapshot.put("proposedAction", proposedAction);
        if (report != null) snapshot.put("report", report);
        return Map.copyOf(snapshot);
    }

    public String error() { return error; }

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
    public void verification(AgentTaskStatus value) { verifiedStatus = value; }
    public void completeVerification() {
        if (verifiedStatus == null) {
            fail("agent verification result is missing");
        } else {
            status = verifiedStatus;
        }
    }
    public void report(String value) { report = value; }
}
