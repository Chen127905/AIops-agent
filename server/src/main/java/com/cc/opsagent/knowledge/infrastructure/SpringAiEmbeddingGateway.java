package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingGateway(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts).stream()
                .map(float[]::clone)
                .toList();
    }
}
