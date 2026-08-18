package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

@Component
public class AgentEventStream {

    private final Map<Long, CopyOnWriteArraySet<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public Subscription subscribe(
            long taskId,
            long afterSequence,
            Consumer<AgentEvent> consumer) {
        if (taskId <= 0 || afterSequence < 0 || consumer == null) {
            throw new IllegalArgumentException("valid task, sequence and consumer are required");
        }
        Subscription subscription = new Subscription(
                taskId, afterSequence, consumer);
        subscription.onClose = () -> remove(taskId, subscription);
        subscriptions.computeIfAbsent(taskId, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);
        return subscription;
    }

    public void publish(AgentEvent event) {
        CopyOnWriteArraySet<Subscription> taskSubscriptions =
                subscriptions.get(event.taskId());
        if (taskSubscriptions != null) {
            taskSubscriptions.forEach(subscription -> {
                try {
                    subscription.accept(event);
                } catch (RuntimeException exception) {
                    subscription.close();
                }
            });
        }
    }

    int subscriberCount(long taskId) {
        CopyOnWriteArraySet<Subscription> taskSubscriptions =
                subscriptions.get(taskId);
        return taskSubscriptions == null ? 0 : taskSubscriptions.size();
    }

    private void remove(long taskId, Subscription subscription) {
        CopyOnWriteArraySet<Subscription> taskSubscriptions =
                subscriptions.get(taskId);
        if (taskSubscriptions == null) return;
        if (subscription != null) taskSubscriptions.remove(subscription);
        if (taskSubscriptions.isEmpty()) subscriptions.remove(taskId, taskSubscriptions);
    }

    public static final class Subscription implements AutoCloseable {

        private final long taskId;
        private final Consumer<AgentEvent> consumer;
        private final TreeMap<Long, AgentEvent> buffered = new TreeMap<>();
        private long lastSequence;
        private boolean active;
        private boolean closed;
        private Runnable onClose;

        private Subscription(
                long taskId,
                long lastSequence,
                Consumer<AgentEvent> consumer) {
            this.taskId = taskId;
            this.lastSequence = lastSequence;
            this.consumer = consumer;
        }

        public synchronized void activate(List<AgentEvent> replay) {
            if (closed || active) return;
            List<AgentEvent> ordered = new ArrayList<>(replay);
            ordered.addAll(buffered.values());
            ordered.stream()
                    .filter(event -> event.taskId() == taskId)
                    .sorted(Comparator.comparingLong(AgentEvent::sequence))
                    .forEach(this::deliver);
            buffered.clear();
            active = true;
        }

        private synchronized void accept(AgentEvent event) {
            if (closed || event.sequence() <= lastSequence) return;
            if (!active) {
                buffered.put(event.sequence(), event);
                return;
            }
            deliver(event);
        }

        private void deliver(AgentEvent event) {
            if (event.sequence() <= lastSequence) return;
            consumer.accept(event);
            lastSequence = event.sequence();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            buffered.clear();
            onClose.run();
        }
    }
}
