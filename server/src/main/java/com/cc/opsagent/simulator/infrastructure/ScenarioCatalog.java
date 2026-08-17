package com.cc.opsagent.simulator.infrastructure;

import com.cc.opsagent.simulator.domain.OpsScenario;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioCatalog {

    private static final String RESOURCE_PATTERN = "classpath*:scenarios/*.yml";
    private static final List<String> REQUIRED_FIELDS = List.of(
            "key",
            "service",
            "category",
            "severity",
            "initialState",
            "health",
            "metrics",
            "logs",
            "dependencies",
            "rootCause",
            "expectedTools",
            "forbiddenTools",
            "requiresApproval",
            "approvedOperation",
            "recoveredState");

    private final Map<String, OpsScenario> scenarios;

    public ScenarioCatalog() {
        this(loadClasspathYaml());
    }

    private ScenarioCatalog(List<String> yamlDocuments) {
        this.scenarios = parse(yamlDocuments);
    }

    static ScenarioCatalog fromYaml(List<String> yamlDocuments) {
        return new ScenarioCatalog(List.copyOf(yamlDocuments));
    }

    public List<OpsScenario> all() {
        return List.copyOf(scenarios.values());
    }

    public OpsScenario require(String scenarioKey) {
        OpsScenario scenario = scenarios.get(scenarioKey);
        if (scenario == null) {
            throw new IllegalArgumentException(
                    "Unknown scenario key: " + scenarioKey);
        }
        return scenario;
    }

    private static List<String> loadClasspathYaml() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(RESOURCE_PATTERN);
            if (resources.length == 0) {
                throw new IllegalStateException(
                        "No scenario resources found at " + RESOURCE_PATTERN);
            }
            List<String> documents = new ArrayList<>(resources.length);
            for (Resource resource : resources) {
                documents.add(resource.getContentAsString(StandardCharsets.UTF_8));
            }
            return documents;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load scenario resources", exception);
        }
    }

    private static Map<String, OpsScenario> parse(List<String> yamlDocuments) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, OpsScenario> parsed = new LinkedHashMap<>();
        for (String yaml : yamlDocuments) {
            OpsScenario scenario = parseOne(mapper, yaml);
            OpsScenario duplicate = parsed.putIfAbsent(scenario.key(), scenario);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate scenario key: " + scenario.key());
            }
        }
        return Map.copyOf(parsed);
    }

    private static OpsScenario parseOne(ObjectMapper mapper, String yaml) {
        try {
            JsonNode root = mapper.readTree(yaml);
            validateRequiredFields(root);
            OpsScenario scenario = mapper.treeToValue(root, OpsScenario.class);
            validateValues(scenario);
            return scenario;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid scenario fixture: " + exception.getMessage(), exception);
        }
    }

    private static void validateRequiredFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("scenario root must be an object");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!root.hasNonNull(field)) {
                throw new IllegalArgumentException(
                        "required field is missing: " + field);
            }
        }
    }

    private static void validateValues(OpsScenario scenario) {
        requireText("key", scenario.key());
        requireText("service", scenario.service());
        requireText("category", scenario.category());
        requireText("rootCause", scenario.rootCause());
        if (scenario.severity() == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (scenario.initialState() != com.cc.opsagent.simulator.domain.ScenarioState.FAULTED) {
            throw new IllegalArgumentException("initialState must be FAULTED");
        }
        if (scenario.recoveredState() != com.cc.opsagent.simulator.domain.ScenarioState.RECOVERED) {
            throw new IllegalArgumentException("recoveredState must be RECOVERED");
        }
        if (scenario.health() == null || scenario.approvedOperation() == null) {
            throw new IllegalArgumentException(
                    "health and approvedOperation must not be null");
        }
        if (scenario.metrics().isEmpty()
                || scenario.logs().isEmpty()
                || scenario.dependencies().isEmpty()
                || scenario.expectedTools().isEmpty()
                || scenario.forbiddenTools().isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence and tool policy collections must not be empty");
        }
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
