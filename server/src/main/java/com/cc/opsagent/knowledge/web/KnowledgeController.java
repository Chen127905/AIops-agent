package com.cc.opsagent.knowledge.web;

import com.cc.opsagent.knowledge.application.BuiltInKnowledgeService;
import com.cc.opsagent.knowledge.application.DocumentIngestionService;
import com.cc.opsagent.knowledge.application.EvidenceChunk;
import com.cc.opsagent.knowledge.application.IngestDocumentCommand;
import com.cc.opsagent.knowledge.application.KnowledgeQuery;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/knowledge")
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class KnowledgeController {

    private final DocumentIngestionService ingestionService;
    private final KnowledgeRetriever retriever;
    private final BuiltInKnowledgeService builtInKnowledge;

    public KnowledgeController(
            DocumentIngestionService ingestionService,
            KnowledgeRetriever retriever,
            BuiltInKnowledgeService builtInKnowledge) {
        this.ingestionService = ingestionService;
        this.retriever = retriever;
        this.builtInKnowledge = builtInKnowledge;
    }

    @PostMapping("/bootstrap")
    @PreAuthorize("hasRole('ADMIN')")
    public BuiltInKnowledgeService.BootstrapResult bootstrap() {
        return builtInKnowledge.initialize();
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentResponse create(@Valid @RequestBody DocumentRequest request) {
        return new DocumentResponse(ingestionService.ingest(request.toCommand(null)));
    }

    @PostMapping("/documents/{documentId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentResponse createVersion(
            @PathVariable long documentId,
            @Valid @RequestBody DocumentRequest request) {
        return new DocumentResponse(
                ingestionService.ingest(request.toCommand(documentId)));
    }

    @GetMapping("/search")
    public List<EvidenceChunk> search(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK) {
        return retriever.retrieve(new KnowledgeQuery(query, topK));
    }

    public record DocumentRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 512) String source,
            @NotBlank @Size(max = 64) String mediaType,
            @NotBlank String content,
            Map<String, String> metadata) {

        IngestDocumentCommand toCommand(Long documentId) {
            return new IngestDocumentCommand(
                    documentId, name, source, mediaType, content, metadata);
        }
    }

    public record DocumentResponse(long documentId) {
    }
}
