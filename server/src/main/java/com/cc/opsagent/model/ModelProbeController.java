package com.cc.opsagent.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Locale;
import java.util.Map;

@Profile("local")
@RestController
@RequestMapping("/api/local/model/probe")
public class ModelProbeController {

    private final ModelGateway modelGateway;

    public ModelProbeController(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    @PostMapping("/{provider}")
    public ModelReply call(
            @PathVariable String provider,
            @Valid @RequestBody ProbeRequest request) {
        return modelGateway.call(
                parseProvider(provider),
                new ModelRequest(request.prompt(), Map.of("source", "local-probe")));
    }

    @GetMapping(
            value = "/{provider}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @PathVariable String provider,
            @RequestParam(defaultValue = "Reply with exactly: ok") String prompt) {
        return modelGateway.stream(
                parseProvider(provider),
                new ModelRequest(prompt, Map.of("source", "local-probe")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidRequest(IllegalArgumentException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    private ModelProvider parseProvider(String provider) {
        try {
            return ModelProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Unsupported model provider: " + provider, exception);
        }
    }

    public record ProbeRequest(@NotBlank String prompt) {
    }

    public record ErrorResponse(String message) {
    }
}
