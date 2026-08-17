package com.cc.opsagent.knowledge.application;

import java.util.Map;

public record EvidenceChunk(
        long tenantId,
        long documentId,
        int documentVersion,
        int chunkIndex,
        String source,
        String content,
        Map<String, String> metadata,
        double score,
        String citationId) {

    public EvidenceChunk {
        metadata = Map.copyOf(metadata);
    }
}
