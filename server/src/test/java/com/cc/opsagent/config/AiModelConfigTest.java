package com.cc.opsagent.config;

import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import com.cc.opsagent.knowledge.infrastructure.LocalHashEmbeddingGateway;
import com.cc.opsagent.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(AiModelConfig.class);

    @Test
    void startsWithoutAnyAiCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelGateway.class);
            assertThat(context).doesNotHaveBean("qwenChatModel");
            assertThat(context).doesNotHaveBean("deepseekChatModel");
            assertThat(context).doesNotHaveBean(ChatModel.class);
            assertThat(context).hasSingleBean(EmbeddingGateway.class);
            assertThat(context.getBean(EmbeddingGateway.class))
                    .isInstanceOf(LocalHashEmbeddingGateway.class);
        });
    }

    @Test
    void createsOnlyQwenWhenOnlyDashScopeKeyIsPresent() {
        contextRunner
                .withPropertyValues(
                        "app.ai.qwen.api-key=test-qwen-key",
                        "app.ai.qwen.base-url=https://dashscope.example",
                        "app.ai.qwen.model=qwen-test",
                        "app.ai.embedding.provider=QWEN")
                .run(context -> {
                    assertThat(context).hasBean("qwenChatModel");
                    assertThat(context).hasBean("qwenEmbeddingModel");
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context).hasSingleBean(EmbeddingGateway.class);
                    assertThat(context).doesNotHaveBean("deepseekChatModel");
                });
    }

    @Test
    void createsOnlyDeepSeekWhenOnlyItsKeyIsPresent() {
        contextRunner
                .withPropertyValues(
                        "app.ai.deepseek.api-key=test-deepseek-key",
                        "app.ai.deepseek.base-url=https://deepseek.example",
                        "app.ai.deepseek.model=deepseek-test")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("qwenChatModel");
                    assertThat(context).hasBean("deepseekChatModel");
                });
    }
}
