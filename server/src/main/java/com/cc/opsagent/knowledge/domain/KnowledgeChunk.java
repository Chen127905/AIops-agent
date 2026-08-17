package com.cc.opsagent.knowledge.domain;

import java.util.Map;

public record KnowledgeChunk(
        long tenantId,
        long documentId,
        int documentVersion,
        int chunkIndex,
        String source,
        String content,
        Map<String, String> metadata,
        float[] embedding,
        boolean published) {

    public KnowledgeChunk {
        metadata = Map.copyOf(metadata);
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
