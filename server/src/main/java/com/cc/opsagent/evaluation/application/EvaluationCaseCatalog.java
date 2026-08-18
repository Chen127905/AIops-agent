package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationGroup;
import com.cc.opsagent.simulator.infrastructure.ScenarioCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Collectors;

@Component
public class EvaluationCaseCatalog {

    private static final String RESOURCE = "evaluation/baseline-cases.json";
    private final Map<String, EvaluationCase> cases;

    @Autowired
    public EvaluationCaseCatalog(ScenarioCatalog scenarios) {
        this(load(), scenarios);
    }

    EvaluationCaseCatalog(List<EvaluationCase> source, ScenarioCatalog scenarios) {
        Map<String, EvaluationCase> indexed = new LinkedHashMap<>();
        for (EvaluationCase evaluationCase : source) {
            scenarios.require(evaluationCase.scenarioKey());
            if (indexed.putIfAbsent(evaluationCase.id(), evaluationCase) != null) {
                throw new IllegalStateException(
                        "Duplicate evaluation case ID: " + evaluationCase.id());
            }
        }
        if (indexed.size() < 30) {
            throw new IllegalStateException(
                    "Evaluation baseline must contain at least 30 cases");
        }
        Set<EvaluationGroup> groups = indexed.values().stream()
                .map(EvaluationCase::group).collect(Collectors.toSet());
        if (!groups.containsAll(Set.of(EvaluationGroup.values()))) {
            throw new IllegalStateException(
                    "Evaluation baseline must cover every evaluation group");
        }
        cases = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    public List<EvaluationCase> all() {
        return List.copyOf(cases.values());
    }

    public List<EvaluationCase> select(Set<String> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) return all();
        for (String id : caseIds) {
            if (!cases.containsKey(id)) {
                throw new IllegalArgumentException("Unknown evaluation case: " + id);
            }
        }
        List<EvaluationCase> selected = cases.values().stream()
                .filter(value -> caseIds.contains(value.id())).toList();
        return List.copyOf(selected);
    }

    private static List<EvaluationCase> load() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readValue(
                    input, new TypeReference<List<EvaluationCase>>() { });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load evaluation baseline", exception);
        }
    }
}
