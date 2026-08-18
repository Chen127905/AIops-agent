package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import com.cc.opsagent.security.SensitiveDataRedactor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private final EmbeddingModel embeddingModel;
    private final SensitiveDataRedactor redactor;

    public SpringAiEmbeddingGateway(EmbeddingModel embeddingModel) {
        this(embeddingModel, new SensitiveDataRedactor());
    }

    public SpringAiEmbeddingGateway(
            EmbeddingModel embeddingModel,
            SensitiveDataRedactor redactor) {
        this.embeddingModel = embeddingModel;
        this.redactor = redactor;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<String> safeTexts = texts.stream().map(redactor::redact).toList();
        return embeddingModel.embed(safeTexts).stream()
                .map(float[]::clone)
                .toList();
    }
}
