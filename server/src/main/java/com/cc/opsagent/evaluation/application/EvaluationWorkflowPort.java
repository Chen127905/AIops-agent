package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationObservation;

public interface EvaluationWorkflowPort {

    default EvaluationRunRequest prepare(EvaluationRunRequest request) {
        return request;
    }

    EvaluationObservation execute(
            EvaluationCase evaluationCase,
            EvaluationRunRequest request);
}
