package com.cc.opsagent.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringAiModelGatewayTest {

    private final ChatModel qwen = mock(ChatModel.class);
    private final ChatModel deepseek = mock(ChatModel.class);

    @Test
    void routesSynchronousCallToRequestedProvider() {
        when(qwen.call(any(Prompt.class))).thenReturn(response("qwen-ok"));
        SpringAiModelGateway gateway = new SpringAiModelGateway(Map.of(
                ModelProvider.QWEN, qwen,
                ModelProvider.DEEPSEEK, deepseek));

        ModelReply reply = gateway.call(
                ModelProvider.QWEN,
                new ModelRequest("reply ok", Map.of("traceId", "trace-1")));

        assertThat(reply.provider()).isEqualTo(ModelProvider.QWEN);
        assertThat(reply.content()).isEqualTo("qwen-ok");
        verifyNoInteractions(deepseek);
    }

    @Test
    void routesStreamingCallAndDropsEmptyChunks() {
        when(deepseek.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("deep"), response(""), response("seek")));
        SpringAiModelGateway gateway = new SpringAiModelGateway(Map.of(
                ModelProvider.QWEN, qwen,
                ModelProvider.DEEPSEEK, deepseek));

        Flux<String> stream = gateway.stream(
                ModelProvider.DEEPSEEK,
                new ModelRequest("stream ok", Map.of()));

        StepVerifier.create(stream)
                .expectNext("deep", "seek")
                .verifyComplete();
        verifyNoInteractions(qwen);
    }

    @Test
    void rejectsProviderThatWasNotConfigured() {
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                Map.of(ModelProvider.QWEN, qwen));

        assertThatThrownBy(() -> gateway.call(
                ModelProvider.DEEPSEEK,
                new ModelRequest("reply ok", Map.of())))
                .isInstanceOf(ModelProviderUnavailableException.class)
                .hasMessageContaining("DEEPSEEK");
    }

    @Test
    void preservesProviderModelAndTokenUsageInReply() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("qwen-plus")
                .usage(new DefaultUsage(12, 5, 17))
                .build();
        when(qwen.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))), metadata));
        SpringAiModelGateway gateway = new SpringAiModelGateway(
                Map.of(ModelProvider.QWEN, qwen));

        ModelReply reply = gateway.call(
                ModelProvider.QWEN,
                new ModelRequest("reply ok", Map.of()));

        assertThat(reply.model()).isEqualTo("qwen-plus");
        assertThat(reply.usage()).isEqualTo(new ModelUsage(12, 5, 17));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))));
    }
}
