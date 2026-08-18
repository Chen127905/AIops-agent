package com.cc.opsagent.security;

import com.cc.opsagent.knowledge.infrastructure.SpringAiEmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingBoundarySecurityTest {

    @Test
    void redactsSecretsBeforeSendingTextToTheEmbeddingProvider() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1.0f, 0.0f}));
        SpringAiEmbeddingGateway gateway = new SpringAiEmbeddingGateway(
                model, new SensitiveDataRedactor());

        gateway.embed(List.of("runbook api_key=sk-live-abcdef123456"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).embed(captor.capture());
        assertThat(captor.getValue().getFirst())
                .contains("[REDACTED]")
                .doesNotContain("sk-live-abcdef123456");
    }
}
