package com.cc.opsagent.evaluation.application;

public class EvaluationRunNotFoundException extends RuntimeException {

    public EvaluationRunNotFoundException(String runId) {
        super("evaluation run was not found: " + runId);
    }
}
