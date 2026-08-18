package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventStreamTest {

    @Test
    void mergesReplayWithBufferedLiveEventsWithoutDuplicates() {
        AgentEventStream stream = new AgentEventStream();
        List<Long> received = new ArrayList<>();
        AgentEventStream.Subscription subscription =
                stream.subscribe(100, 2, event -> received.add(event.sequence()));

        stream.publish(event(4));
        subscription.activate(List.of(event(3), event(4)));
        stream.publish(event(5));

        assertThat(received).containsExactly(3L, 4L, 5L);
    }

    @Test
    void disconnectRemovesOnlyTheSubscription() {
        AgentEventStream stream = new AgentEventStream();
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        AgentEventStream.Subscription one =
                stream.subscribe(100, 0, event -> first.add(event.sequence()));
        AgentEventStream.Subscription two =
                stream.subscribe(100, 0, event -> second.add(event.sequence()));
        one.activate(List.of());
        two.activate(List.of());

        one.close();
        stream.publish(event(1));

        assertThat(first).isEmpty();
        assertThat(second).containsExactly(1L);
        assertThat(stream.subscriberCount(100)).isEqualTo(1);
    }

    private AgentEvent event(long sequence) {
        return new AgentEvent(
                sequence, 1, 100, sequence, "NODE_COMPLETED",
                Map.of("sequence", sequence), Instant.now());
    }
}
