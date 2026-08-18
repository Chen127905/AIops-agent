package com.cc.opsagent.config;

import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.model.SpringAiModelGateway;
import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import com.cc.opsagent.knowledge.infrastructure.SpringAiEmbeddingGateway;
import com.cc.opsagent.security.SensitiveDataRedactor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class AiModelConfig {

    @Bean("qwenChatModel")
    @Conditional(QwenApiKeyConfigured.class)
    public ChatModel qwenChatModel(
            @Value("${app.ai.qwen.api-key}") String apiKey,
            @Value("${app.ai.qwen.base-url}") String baseUrl,
            @Value("${app.ai.qwen.model}") String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .temperature(0.1)
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Bean("qwenEmbeddingModel")
    @Conditional(QwenApiKeyConfigured.class)
    public EmbeddingModel qwenEmbeddingModel(
            @Value("${app.ai.qwen.api-key}") String apiKey,
            @Value("${app.ai.qwen.base-url}") String baseUrl,
            @Value("${app.ai.qwen.embedding-model:text-embedding-v4}") String model,
            @Value("${app.ai.qwen.embedding-dimensions:1024}") int dimensions) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .dimensions(dimensions)
                .maxRetries(0)
                .build();
        return OpenAiEmbeddingModel.builder()
                .options(options)
                .build();
    }

    @Bean("deepseekChatModel")
    @Conditional(DeepSeekApiKeyConfigured.class)
    public ChatModel deepseekChatModel(
            @Value("${app.ai.deepseek.api-key}") String apiKey,
            @Value("${app.ai.deepseek.base-url}") String baseUrl,
            @Value("${app.ai.deepseek.model}") String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .temperature(0.1)
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Bean
    public ModelGateway modelGateway(
            @Qualifier("qwenChatModel") ObjectProvider<ChatModel> qwen,
            @Qualifier("deepseekChatModel") ObjectProvider<ChatModel> deepseek) {
        Map<ModelProvider, ChatModel> models = new EnumMap<>(ModelProvider.class);
        qwen.ifAvailable(model -> models.put(ModelProvider.QWEN, model));
        deepseek.ifAvailable(model -> models.put(ModelProvider.DEEPSEEK, model));
        return new SpringAiModelGateway(models);
    }

    @Bean
    public EmbeddingGateway embeddingGateway(
            @Qualifier("qwenEmbeddingModel") ObjectProvider<EmbeddingModel> qwen,
            ObjectProvider<SensitiveDataRedactor> redactors) {
        EmbeddingModel model = qwen.getIfAvailable();
        if (model == null) {
            return texts -> {
                throw new IllegalStateException(
                        "Qwen embedding is unavailable because no API key is configured");
            };
        }
        return new SpringAiEmbeddingGateway(
                model, redactors.getIfAvailable(SensitiveDataRedactor::new));
    }

    static final class QwenApiKeyConfigured implements Condition {

        @Override
        public boolean matches(
                ConditionContext context,
                AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment()
                    .getProperty("app.ai.qwen.api-key"));
        }
    }

    static final class DeepSeekApiKeyConfigured implements Condition {

        @Override
        public boolean matches(
                ConditionContext context,
                AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment()
                    .getProperty("app.ai.deepseek.api-key"));
        }
    }
}
