CREATE TABLE knowledge_chunk
(
    tenant_id        BIGINT       NOT NULL,
    document_id      BIGINT       NOT NULL,
    document_version INTEGER      NOT NULL,
    chunk_index      INTEGER      NOT NULL,
    source           TEXT         NOT NULL,
    content          TEXT         NOT NULL,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    embedding        vector(1024) NOT NULL,
    published        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at     TIMESTAMPTZ  NULL,
    PRIMARY KEY (tenant_id, document_id, document_version, chunk_index),
    CONSTRAINT ck_knowledge_chunk_version CHECK (document_version > 0),
    CONSTRAINT ck_knowledge_chunk_index CHECK (chunk_index >= 0)
);

CREATE INDEX idx_knowledge_chunk_active_document
    ON knowledge_chunk (tenant_id, document_id, published, document_version, chunk_index);

CREATE INDEX idx_knowledge_chunk_embedding_hnsw
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
