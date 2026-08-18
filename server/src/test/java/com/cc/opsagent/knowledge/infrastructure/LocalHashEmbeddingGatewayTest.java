package com.cc.opsagent.knowledge.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHashEmbeddingGatewayTest {

    private final LocalHashEmbeddingGateway gateway =
            new LocalHashEmbeddingGateway();

    @Test
    void createsDeterministicNormalizedEmbeddings() {
        float[] first = gateway.embed(List.of("Redis 连接池超时")).getFirst();
        float[] second = gateway.embed(List.of("Redis 连接池超时")).getFirst();

        assertThat(first).hasSize(1024).containsExactly(second);
        assertThat(cosine(first, second)).isCloseTo(
                1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void sharedTermsRankAboveUnrelatedText() {
        List<float[]> vectors = gateway.embed(List.of(
                "Redis connection pool timeout troubleshooting",
                "Redis timeout and connection pool exhaustion",
                "disk cleanup and retention policy"));

        assertThat(cosine(vectors.get(0), vectors.get(1)))
                .isGreaterThan(cosine(vectors.get(0), vectors.get(2)));
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> gateway.embed(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double cosine(float[] left, float[] right) {
        double result = 0.0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }
}
