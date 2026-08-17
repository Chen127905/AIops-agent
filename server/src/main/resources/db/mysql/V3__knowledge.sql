CREATE TABLE knowledge_document
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT UNSIGNED NOT NULL,
    name               VARCHAR(160)    NOT NULL,
    source             VARCHAR(512)    NOT NULL,
    media_type         VARCHAR(64)     NOT NULL,
    active_version     INT UNSIGNED    NOT NULL DEFAULT 0,
    processing_version INT UNSIGNED    NULL,
    status             VARCHAR(24)     NOT NULL DEFAULT 'PROCESSING',
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_tenant_name (tenant_id, name),
    UNIQUE KEY uk_knowledge_document_id_tenant (id, tenant_id),
    CONSTRAINT ck_knowledge_document_status
        CHECK (status IN ('PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT fk_knowledge_document_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_document_version
(
    document_id  BIGINT UNSIGNED NOT NULL,
    tenant_id    BIGINT UNSIGNED NOT NULL,
    version      INT UNSIGNED    NOT NULL,
    status       VARCHAR(24)     NOT NULL,
    content_hash CHAR(64)        NOT NULL,
    chunk_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    metadata     JSON            NOT NULL,
    error_summary VARCHAR(512)   NULL,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6)     NULL,
    PRIMARY KEY (document_id, version),
    KEY idx_knowledge_version_tenant_status (tenant_id, status, created_at),
    CONSTRAINT ck_knowledge_document_version_status
        CHECK (status IN ('PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT fk_knowledge_version_document_tenant
        FOREIGN KEY (document_id, tenant_id)
            REFERENCES knowledge_document (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
