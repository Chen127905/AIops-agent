package com.cc.opsagent.knowledge.application;

import java.util.Map;

public record IngestDocumentCommand(
        Long documentId,
        String name,
        String source,
        String mediaType,
        String content,
        Map<String, String> metadata) {

    public IngestDocumentCommand {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
