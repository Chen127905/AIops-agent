package com.cc.opsagent.knowledge.application;

import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.knowledge.domain.KnowledgeDocument;
import com.cc.opsagent.knowledge.infrastructure.KnowledgeDocumentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class BuiltInKnowledgeService {

    private static final String MANIFEST = "knowledge/initial-runbooks.json";

    private final DocumentIngestionService ingestion;
    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public BuiltInKnowledgeService(
            DocumentIngestionService ingestion,
            KnowledgeDocumentMapper documentMapper) {
        this.ingestion = ingestion;
        this.documentMapper = documentMapper;
    }

    public BootstrapResult initialize() {
        long tenantId = TenantContext.requireTenantId();
        List<SeedDocument> documents = loadManifest();
        List<PublishedDocument> results = new ArrayList<>();
        int published = 0;

        for (SeedDocument document : documents) {
            KnowledgeDocument existing = documentMapper.selectBySource(
                    tenantId, document.source());
            if (existing != null) {
                results.add(skipped(existing.getId(), document));
                continue;
            }

            try {
                long documentId = ingestion.ingest(new IngestDocumentCommand(
                        null,
                        document.name(),
                        document.source(),
                        document.mediaType(),
                        document.content(),
                        document.metadata()));
                results.add(new PublishedDocument(
                        documentId,
                        document.name(),
                        document.source(),
                        true));
                published++;
            } catch (DuplicateKeyException exception) {
                KnowledgeDocument concurrent = documentMapper.selectBySource(
                        tenantId, document.source());
                if (concurrent == null) {
                    throw exception;
                }
                results.add(skipped(concurrent.getId(), document));
            }
        }

        return new BootstrapResult(
                documents.size(),
                published,
                documents.size() - published,
                List.copyOf(results));
    }

    private PublishedDocument skipped(
            long documentId,
            SeedDocument document) {
        return new PublishedDocument(
                documentId,
                document.name(),
                document.source(),
                false);
    }

    private List<SeedDocument> loadManifest() {
        ClassPathResource resource = new ClassPathResource(MANIFEST);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() { });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "built-in knowledge manifest is unavailable", exception);
        }
    }

    private record SeedDocument(
            String name,
            String source,
            String mediaType,
            Map<String, String> metadata,
            String content) {
    }

    public record BootstrapResult(
            int total,
            int published,
            int skipped,
            List<PublishedDocument> documents) {
    }

    public record PublishedDocument(
            long documentId,
            String name,
            String source,
            boolean published) {
    }
}
