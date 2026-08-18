CREATE TABLE security_audit_log
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT UNSIGNED NULL,
    user_id       BIGINT UNSIGNED NULL,
    event_type    VARCHAR(64)     NOT NULL,
    outcome       VARCHAR(24)     NOT NULL,
    resource_type VARCHAR(64)     NULL,
    resource_id   VARCHAR(128)    NULL,
    details       JSON            NOT NULL,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_security_audit_tenant_time (tenant_id, created_at),
    KEY idx_security_audit_event_time (event_type, created_at),
    CONSTRAINT ck_security_audit_outcome CHECK (outcome IN (
        'SUCCEEDED', 'REJECTED', 'FAILED', 'REQUESTED'
    ))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
