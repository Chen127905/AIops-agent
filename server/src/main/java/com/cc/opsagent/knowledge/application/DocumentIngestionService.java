package com.cc.opsagent.knowledge.application;

import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.knowledge.domain.KnowledgeChunk;
import com.cc.opsagent.knowledge.domain.KnowledgeDocument;
import com.cc.opsagent.knowledge.infrastructure.KnowledgeChunkRepository;
import com.cc.opsagent.knowledge.infrastructure.KnowledgeDocumentMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class DocumentIngestionService {

    static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    static final int MAX_CHUNK_CHARACTERS = 1200;
    static final int EMBEDDING_BATCH_SIZE = 10;
    static final int EMBEDDING_DIMENSIONS = 1024;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingGateway embeddingGateway;
    private final ObjectMapper objectMapper;

    public DocumentIngestionService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkRepository chunkRepository,
            EmbeddingGateway embeddingGateway) {
        this.documentMapper = documentMapper;
        this.chunkRepository = chunkRepository;
        this.embeddingGateway = embeddingGateway;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public long ingest(IngestDocumentCommand command) {
        validate(command);
        long tenantId = TenantContext.requireTenantId();
        String normalizedContent = normalize(command.content());
        List<String> contents = chunk(normalizedContent);
        DocumentVersion documentVersion = beginVersion(
                tenantId, command, normalizedContent);
        try {
            List<float[]> embeddings = embed(contents);
            List<KnowledgeChunk> chunks = createChunks(
                    tenantId, documentVersion, command, contents, embeddings);
            chunkRepository.stage(chunks);
            publishMetadata(tenantId, documentVersion, chunks.size());
            chunkRepository.publish(
                    tenantId,
                    documentVersion.documentId(),
                    documentVersion.version(),
                    chunks.size());
            return documentVersion.documentId();
        } catch (RuntimeException exception) {
            chunkRepository.discard(
                    tenantId,
                    documentVersion.documentId(),
                    documentVersion.version());
            throw exception;
        }
    }

    private DocumentVersion beginVersion(
            long tenantId,
            IngestDocumentCommand command,
            String normalizedContent) {
        KnowledgeDocument document;
        int version;
        if (command.documentId() == null) {
            document = new KnowledgeDocument();
            document.setTenantId(tenantId);
            document.setName(command.name().trim());
            document.setSource(command.source().trim());
            document.setMediaType(command.mediaType().trim());
            if (documentMapper.insertNew(document) != 1) {
                throw new IllegalStateException("knowledge document could not be created");
            }
            version = 1;
        } else {
            document = documentMapper.selectForUpdate(tenantId, command.documentId());
            if (document == null) {
                throw new IllegalArgumentException(
                        "knowledge document was not found for the authenticated tenant");
            }
            version = document.getActiveVersion() + 1;
            if (documentMapper.beginVersion(
                    tenantId, document.getId(), version) != 1) {
                throw new IllegalStateException(
                        "another knowledge ingestion is already processing");
            }
        }
        if (documentMapper.insertVersion(
                tenantId,
                document.getId(),
                version,
                sha256(normalizedContent),
                metadataJson(command.metadata())) != 1) {
            throw new IllegalStateException("knowledge document version could not be created");
        }
        return new DocumentVersion(document.getId(), version);
    }

    private void publishMetadata(
            long tenantId,
            DocumentVersion version,
            int chunkCount) {
        if (documentMapper.publishVersionRecord(
                tenantId,
                version.documentId(),
                version.version(),
                chunkCount) != 1) {
            throw new IllegalStateException("knowledge version publication was stale");
        }
        if (documentMapper.publishDocument(
                tenantId,
                version.documentId(),
                version.version()) != 1) {
            throw new IllegalStateException("knowledge document publication was stale");
        }
    }

    private List<float[]> embed(List<String> contents) {
        List<float[]> embeddings = new ArrayList<>(contents.size());
        for (int start = 0; start < contents.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, contents.size());
            List<float[]> batch = embeddingGateway.embed(contents.subList(start, end));
            if (batch.size() != end - start) {
                throw new IllegalStateException(
                        "embedding provider returned a mismatched result count");
            }
            for (float[] embedding : batch) {
                if (embedding == null || embedding.length != EMBEDDING_DIMENSIONS) {
                    throw new IllegalStateException(
                            "embedding provider must return 1024-dimensional vectors");
                }
                embeddings.add(embedding.clone());
            }
        }
        return List.copyOf(embeddings);
    }

    private List<KnowledgeChunk> createChunks(
            long tenantId,
            DocumentVersion version,
            IngestDocumentCommand command,
            List<String> contents,
            List<float[]> embeddings) {
        Map<String, String> metadata = new LinkedHashMap<>(command.metadata());
        metadata.put("documentName", command.name().trim());
        metadata.put("mediaType", command.mediaType().trim());
        List<KnowledgeChunk> chunks = new ArrayList<>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            chunks.add(new KnowledgeChunk(
                    tenantId,
                    version.documentId(),
                    version.version(),
                    index,
                    command.source().trim(),
                    contents.get(index),
                    metadata,
                    embeddings.get(index),
                    false));
        }
        return List.copyOf(chunks);
    }

    private void validate(IngestDocumentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("ingest command is required");
        }
        requireText("name", command.name());
        requireText("source", command.source());
        requireText("mediaType", command.mediaType());
        if (command.content() == null || command.content().isBlank()) {
            throw new IllegalArgumentException("document content is empty");
        }
        if (command.content().getBytes(StandardCharsets.UTF_8).length
                > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("document exceeds the 2 MiB UTF-8 limit");
        }
    }

    private String normalize(String content) {
        String normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("document content is empty");
        }
        return normalized;
    }

    private List<String> chunk(String content) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            boolean heading = line.matches("#{1,6}\\s+.*");
            if (heading && !current.isEmpty()) {
                sections.add(current.toString().strip());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().strip());
        }
        List<String> chunks = new ArrayList<>();
        sections.forEach(section -> splitBounded(section, chunks));
        return List.copyOf(chunks);
    }

    private void splitBounded(String section, List<String> chunks) {
        int offset = 0;
        while (offset < section.length()) {
            int end = Math.min(offset + MAX_CHUNK_CHARACTERS, section.length());
            if (end < section.length()) {
                int preferred = Math.max(
                        section.lastIndexOf('\n', end),
                        section.lastIndexOf(' ', end));
                if (preferred > offset + MAX_CHUNK_CHARACTERS / 2) {
                    end = preferred;
                }
            }
            String chunk = section.substring(offset, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            offset = end;
            while (offset < section.length()
                    && Character.isWhitespace(section.charAt(offset))) {
                offset++;
            }
        }
    }

    private String metadataJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata cannot be serialized", exception);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private record DocumentVersion(long documentId, int version) {
    }
}
