ALTER TABLE user_account
    ADD UNIQUE KEY uk_user_account_id_tenant (id, tenant_id);

CREATE TABLE ticket
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT UNSIGNED NOT NULL,
    reporter_id        BIGINT UNSIGNED NOT NULL,
    title              VARCHAR(120)    NOT NULL,
    description        TEXT            NOT NULL,
    affected_service   VARCHAR(128)    NULL,
    category           VARCHAR(64)     NULL,
    severity           VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN',
    status             VARCHAR(32)     NOT NULL DEFAULT 'OPEN',
    resolution_summary TEXT            NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ticket_tenant_status_created_at (tenant_id, status, created_at),
    KEY idx_ticket_tenant_reporter_created_at (tenant_id, reporter_id, created_at),
    CONSTRAINT ck_ticket_severity
        CHECK (severity IN ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_ticket_status
        CHECK (status IN (
            'OPEN', 'TRIAGING', 'DIAGNOSING', 'WAITING_APPROVAL',
            'EXECUTING', 'VERIFYING', 'RESOLVED', 'FAILED',
            'CANCELLED', 'TIMEOUT', 'MANUAL_REQUIRED'
        )),
    CONSTRAINT fk_ticket_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_ticket_reporter_tenant
        FOREIGN KEY (reporter_id, tenant_id) REFERENCES user_account (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
