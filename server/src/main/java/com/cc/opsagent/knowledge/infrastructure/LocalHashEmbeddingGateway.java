package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.knowledge.application.EmbeddingGateway;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free embedding for local demonstrations and deterministic smoke tests.
 * It preserves lexical similarity; it is not a replacement for a semantic model.
 */
public final class LocalHashEmbeddingGateway implements EmbeddingGateway {

    public static final int DIMENSIONS = 1024;

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("embedding texts are required");
        }
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("embedding text must not be blank");
            }
            embeddings.add(embedOne(text));
        }
        return List.copyOf(embeddings);
    }

    private float[] embedOne(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        int[] codePoints = normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .toArray();
        float[] vector = new float[DIMENSIONS];
        for (int index = 0; index < codePoints.length; index++) {
            add(vector, "u:" + codePoints[index], 1.0f);
            if (index + 1 < codePoints.length) {
                add(vector,
                        "b:" + codePoints[index] + ':' + codePoints[index + 1],
                        2.0f);
            }
        }
        if (codePoints.length == 0) {
            vector[0] = 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private void add(float[] vector, String feature, float weight) {
        int hash = mix(feature.hashCode());
        int dimension = Math.floorMod(hash, DIMENSIONS);
        float sign = (hash & 0x40000000) == 0 ? 1.0f : -1.0f;
        vector[dimension] += sign * weight;
    }

    private int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private void normalize(float[] vector) {
        double squaredNorm = 0.0;
        for (float value : vector) {
            squaredNorm += value * value;
        }
        float norm = (float) Math.sqrt(squaredNorm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
    }
}
