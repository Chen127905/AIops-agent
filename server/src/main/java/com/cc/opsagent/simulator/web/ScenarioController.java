package com.cc.opsagent.simulator.web;

import com.cc.opsagent.simulator.domain.OpsScenario;
import com.cc.opsagent.simulator.infrastructure.ScenarioCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioCatalog catalog;

    public ScenarioController(ScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ScenarioResponse> all() {
        return catalog.all().stream().map(ScenarioResponse::from).toList();
    }

    public record ScenarioResponse(
            String key,
            String service,
            String category,
            String severity,
            boolean requiresApproval) {

        static ScenarioResponse from(OpsScenario scenario) {
            return new ScenarioResponse(
                    scenario.key(), scenario.service(), scenario.category(),
                    scenario.severity().name(), scenario.requiresApproval());
        }
    }
}
