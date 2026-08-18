package com.cc.opsagent.agent.graph;

import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.RecoveryCheckpoint;
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
    private String resumeAfterNode;
    private boolean resumePointReached = true;
    private int steps;
    private int tokens;
    private String category;
    private String urgency;
    private List<EvidenceChunk> evidence = List.of();
    private List<String> plannedTools = List.of();
    private final List<ToolObservation> observations = new ArrayList<>();
    private String rootCause;
    private String proposedAction;
    private String diagnosisSummary;
    private List<String> remediationSteps = List.of();
    private List<String> verificationSteps = List.of();
    private String rollbackPlan;
    private Map<String, Object> actionArguments = Map.of();
    private double confidence;
    private AgentTaskStatus status = AgentTaskStatus.RUNNING;
    private AgentTaskStatus verifiedStatus;
    private String report;
    private String error;

    public OpsAgentState(AgentTaskCommand command) {
        this.command = command;
        this.startedAtNanos = System.nanoTime();
    }

    public static OpsAgentState recover(
            AgentTaskCommand command,
            RecoveryCheckpoint checkpoint) {
        OpsAgentState state = new OpsAgentState(command);
        state.restore(checkpoint.state());
        state.steps = Math.max(state.steps, checkpoint.completedSequence());
        state.resumeAfterNode = checkpoint.lastCompletedNode();
        state.resumePointReached = false;
        return state;
    }

    public boolean enterStep() {
        if (!controlPoint()) {
            return false;
        }
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

    public boolean controlPoint() {
        if (terminal()) {
            return false;
        }
        return !timedOut();
    }

    public void cancel() {
        if (!terminal()) {
            status = AgentTaskStatus.CANCELLED;
            error = "agent cancellation requested";
        }
    }

    public boolean shouldExecute(String nodeName) {
        if (resumePointReached) {
            return true;
        }
        if (resumeAfterNode.equals(nodeName)) {
            resumePointReached = true;
        }
        return false;
    }

    public boolean terminal() {
        return status.terminal() || status == AgentTaskStatus.WAITING_APPROVAL;
    }

    public void fail(String message) {
        if (status == AgentTaskStatus.TIMED_OUT
                || status == AgentTaskStatus.CANCELLED) {
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
                error,
                actionArguments);
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
        if (diagnosisSummary != null) snapshot.put("diagnosisSummary", diagnosisSummary);
        if (!remediationSteps.isEmpty()) snapshot.put("remediationSteps", remediationSteps);
        if (!verificationSteps.isEmpty()) snapshot.put("verificationSteps", verificationSteps);
        if (rollbackPlan != null) snapshot.put("rollbackPlan", rollbackPlan);
        if (!actionArguments.isEmpty()) snapshot.put("actionArguments", actionArguments);
        if (report != null) snapshot.put("report", report);
        return Map.copyOf(snapshot);
    }

    public Map<String, Object> checkpointSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>(auditSnapshot());
        snapshot.put("confidence", confidence);
        snapshot.put("evidence", evidence);
        snapshot.put("observations", observations);
        if (verifiedStatus != null) {
            snapshot.put("verifiedStatus", verifiedStatus.name());
        }
        if (error != null) snapshot.put("error", error);
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
    public Map<String, Object> actionArguments() { return actionArguments; }
    public double confidence() { return confidence; }
    public void decision(
            String cause,
            String action,
            Map<String, Object> arguments,
            double score) {
        decision(cause, null, action, arguments, score, List.of(), List.of(), null);
    }
    public void decision(
            String cause,
            String summary,
            String action,
            Map<String, Object> arguments,
            double score,
            List<String> remediation,
            List<String> verification,
            String rollback) {
        rootCause = cause;
        diagnosisSummary = summary;
        proposedAction = action;
        actionArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        confidence = score;
        remediationSteps = remediation == null ? List.of() : List.copyOf(remediation);
        verificationSteps = verification == null ? List.of() : List.copyOf(verification);
        rollbackPlan = rollback;
    }
    public String diagnosisSummary() { return diagnosisSummary; }
    public List<String> remediationSteps() { return remediationSteps; }
    public List<String> verificationSteps() { return verificationSteps; }
    public String rollbackPlan() { return rollbackPlan; }
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

    private void restore(Map<String, Object> snapshot) {
        steps = integer(snapshot.get("steps"), 0);
        tokens = integer(snapshot.get("tokens"), 0);
        category = text(snapshot.get("category"));
        urgency = text(snapshot.get("urgency"));
        plannedTools = strings(snapshot.get("plannedTools"));
        rootCause = text(snapshot.get("rootCause"));
        proposedAction = text(snapshot.get("proposedAction"));
        diagnosisSummary = text(snapshot.get("diagnosisSummary"));
        remediationSteps = strings(snapshot.get("remediationSteps"));
        verificationSteps = strings(snapshot.get("verificationSteps"));
        rollbackPlan = text(snapshot.get("rollbackPlan"));
        actionArguments = objectMap(snapshot.get("actionArguments"));
        confidence = decimal(snapshot.get("confidence"), 0);
        report = text(snapshot.get("report"));
        error = text(snapshot.get("error"));
        String storedStatus = text(snapshot.get("status"));
        if (storedStatus != null) {
            status = AgentTaskStatus.valueOf(storedStatus);
        }
        String storedVerification = text(snapshot.get("verifiedStatus"));
        if (storedVerification != null) {
            verifiedStatus = AgentTaskStatus.valueOf(storedVerification);
        }
        evidence = restoreEvidence(snapshot.get("evidence"));
        observations.addAll(restoreObservations(snapshot.get("observations")));
    }

    private List<EvidenceChunk> restoreEvidence(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<EvidenceChunk> restored = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = objectMap(item);
            restored.add(new EvidenceChunk(
                    longValue(map.get("tenantId"), command.tenantId()),
                    longValue(map.get("documentId"), 0),
                    integer(map.get("documentVersion"), 0),
                    integer(map.get("chunkIndex"), 0),
                    text(map.get("source")),
                    text(map.get("content")),
                    stringMap(map.get("metadata")),
                    decimal(map.get("score"), 0),
                    text(map.get("citationId"))));
        }
        return List.copyOf(restored);
    }

    private List<ToolObservation> restoreObservations(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<ToolObservation> restored = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = objectMap(item);
            restored.add(new ToolObservation(
                    text(map.get("toolName")),
                    Boolean.TRUE.equals(map.get("success")),
                    objectMap(map.get("data")),
                    text(map.get("error"))));
        }
        return List.copyOf(restored);
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(
                String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).toList();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
