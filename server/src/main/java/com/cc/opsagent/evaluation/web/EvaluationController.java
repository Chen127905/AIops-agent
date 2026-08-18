package com.cc.opsagent.evaluation.web;

import com.cc.opsagent.evaluation.application.EvaluationRunNotFoundException;
import com.cc.opsagent.evaluation.application.EvaluationRunRequest;
import com.cc.opsagent.evaluation.application.EvaluationRunSummary;
import com.cc.opsagent.evaluation.application.EvaluationRunner;
import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.model.ModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/evaluations")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {

    private final EvaluationRunner runner;

    public EvaluationController(EvaluationRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/cases")
    public List<EvaluationCase> cases() {
        return runner.cases();
    }

    @PostMapping("/runs")
    public EvaluationRunSummary run(
            @Valid @RequestBody(required = false) RunRequest request) {
        RunRequest value = request == null ? new RunRequest(
                null, null, null, null, null, null) : request;
        return runner.run(value.toCommand());
    }

    @GetMapping("/runs/{runId}")
    public EvaluationRunSummary get(@PathVariable String runId) {
        return runner.get(runId);
    }

    @ExceptionHandler(EvaluationRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() { }

    public record RunRequest(
            EvaluationMode mode,
            ModelProvider provider,
            @Size(max = 128) String model,
            @Size(max = 64) String promptVersion,
            @Size(max = 64) String knowledgeVersion,
            @Size(max = 100) Set<@Size(max = 128) String> caseIds) {

        EvaluationRunRequest toCommand() {
            return new EvaluationRunRequest(
                    mode, provider, model, promptVersion,
                    knowledgeVersion, caseIds);
        }
    }
}
