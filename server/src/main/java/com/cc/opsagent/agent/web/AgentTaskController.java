package com.cc.opsagent.agent.web;

import com.cc.opsagent.agent.application.ActiveTaskExistsException;
import com.cc.opsagent.agent.application.AgentBudget;
import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentEventStream;
import com.cc.opsagent.agent.application.AgentExecutionRejectedException;
import com.cc.opsagent.agent.application.AgentExecutionService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.AgentCancellationService;
import com.cc.opsagent.agent.domain.AgentEvent;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Validated
@RestController
@RequestMapping("/api")
public class AgentTaskController {

    private static final int REPLAY_PAGE_SIZE = 500;

    private final AgentExecutionService executionService;
    private final AgentTaskService taskService;
    private final AgentCancellationService cancellationService;
    private final AgentEventService eventService;
    private final AgentEventStream eventStream;
    private final int defaultMaxSteps;
    private final Duration defaultTimeout;
    private final int defaultMaxTokens;

    public AgentTaskController(
            AgentExecutionService executionService,
            AgentTaskService taskService,
            AgentCancellationService cancellationService,
            AgentEventService eventService,
            AgentEventStream eventStream,
            @Value("${app.agent.defaults.max-steps:12}") int defaultMaxSteps,
            @Value("${app.agent.defaults.timeout:PT3M}") Duration defaultTimeout,
            @Value("${app.agent.defaults.max-tokens:20000}") int defaultMaxTokens) {
        this.executionService = executionService;
        this.taskService = taskService;
        this.cancellationService = cancellationService;
        this.eventService = eventService;
        this.eventStream = eventStream;
        this.defaultMaxSteps = defaultMaxSteps;
        this.defaultTimeout = defaultTimeout;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @PostMapping("/tickets/{ticketId}/agent-tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentTaskResponse start(@PathVariable long ticketId) {
        return AgentTaskResponse.from(executionService.start(
                ticketId,
                new AgentBudget(
                        defaultMaxSteps, defaultTimeout, defaultMaxTokens)));
    }

    @GetMapping("/agent-tasks/{taskId}")
    public AgentTaskResponse get(@PathVariable long taskId) {
        return AgentTaskResponse.from(taskService.get(taskId));
    }

    @GetMapping("/tickets/{ticketId}/agent-tasks/latest")
    public ResponseEntity<AgentTaskResponse> latest(@PathVariable long ticketId) {
        var task = taskService.latestForTicket(ticketId);
        return task == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(AgentTaskResponse.from(task));
    }

    @GetMapping("/agent-tasks/{taskId}/result")
    public AgentTaskResultResponse result(@PathVariable long taskId) {
        var task = taskService.get(taskId);
        return AgentTaskResultResponse.from(task, taskService.steps(taskId));
    }

    @PostMapping("/agent-tasks/{taskId}/cancel")
    public AgentTaskResponse cancel(@PathVariable long taskId) {
        return AgentTaskResponse.from(
                cancellationService.requestCancel(taskId));
    }

    @GetMapping(
            value = "/agent-tasks/{taskId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "0") @Min(0) long after) {
        taskService.get(taskId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<AgentEventStream.Subscription> reference =
                new AtomicReference<>();
        AgentEventStream.Subscription subscription = eventStream.subscribe(
                taskId, after, event -> send(emitter, reference, event));
        reference.set(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        try {
            subscription.activate(replay(taskId, after));
        } catch (RuntimeException exception) {
            subscription.close();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private List<AgentEvent> replay(long taskId, long after) {
        List<AgentEvent> events = new ArrayList<>();
        long cursor = after;
        while (true) {
            List<AgentEvent> page = eventService.after(
                    taskId, cursor, REPLAY_PAGE_SIZE);
            events.addAll(page);
            if (page.size() < REPLAY_PAGE_SIZE) return List.copyOf(events);
            cursor = page.getLast().sequence();
        }
    }

    private void send(
            SseEmitter emitter,
            AtomicReference<AgentEventStream.Subscription> reference,
            AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.eventType())
                    .data(event));
            if ("TASK_COMPLETED".equals(event.eventType())
                    || "TASK_EXECUTION_FAILED".equals(event.eventType())
                    || "TASK_REJECTED".equals(event.eventType())) {
                reference.get().close();
                emitter.complete();
            }
        } catch (IOException | IllegalStateException exception) {
            AgentEventStream.Subscription subscription = reference.get();
            if (subscription != null) subscription.close();
            emitter.completeWithError(exception);
        }
    }

    @ExceptionHandler(ActiveTaskExistsException.class)
    ResponseEntity<Void> activeTaskExists() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(AgentExecutionRejectedException.class)
    ResponseEntity<Void> executionRejected() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
