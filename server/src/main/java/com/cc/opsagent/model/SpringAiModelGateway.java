package com.cc.opsagent.model;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.EnumMap;
import java.util.Map;

public class SpringAiModelGateway implements ModelGateway {

    private final Map<ModelProvider, ChatModel> models;

    public SpringAiModelGateway(Map<ModelProvider, ChatModel> models) {
        EnumMap<ModelProvider, ChatModel> configuredModels =
                new EnumMap<>(ModelProvider.class);
        if (models != null) {
            configuredModels.putAll(models);
        }
        this.models = Map.copyOf(configuredModels);
    }

    @Override
    public ModelReply call(ModelProvider provider, ModelRequest request) {
        ChatResponse response = requireModel(provider).call(toPrompt(request));
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("Model returned no response: " + provider);
        }
        return new ModelReply(
                provider,
                modelName(response.getMetadata()),
                response.getResult().getOutput().getText(),
                usage(response.getMetadata()));
    }

    @Override
    public Flux<String> stream(ModelProvider provider, ModelRequest request) {
        return requireModel(provider)
                .stream(toPrompt(request))
                .filter(response -> response != null && response.getResult() != null)
                .map(response -> response.getResult().getOutput().getText())
                .filter(content -> content != null && !content.isEmpty());
    }

    private ChatModel requireModel(ModelProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Model provider must not be null");
        }
        ChatModel model = models.get(provider);
        if (model == null) {
            throw new ModelProviderUnavailableException(provider);
        }
        return model;
    }

    private Prompt toPrompt(ModelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Model request must not be null");
        }
        return new Prompt(request.prompt());
    }

    private String modelName(ChatResponseMetadata metadata) {
        return metadata == null ? null : metadata.getModel();
    }

    private ModelUsage usage(ChatResponseMetadata metadata) {
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return ModelUsage.unavailable();
        }
        return new ModelUsage(
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }
}
