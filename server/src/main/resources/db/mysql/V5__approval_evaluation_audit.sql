CREATE TABLE approval_request
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT UNSIGNED NOT NULL,
    task_id              BIGINT UNSIGNED NOT NULL,
    checkpoint_id        VARCHAR(128)    NOT NULL,
    scenario_key         VARCHAR(128)    NOT NULL,
    tool_name            VARCHAR(64)     NOT NULL,
    normalized_arguments JSON            NOT NULL,
    arguments_hash       CHAR(64)        NOT NULL,
    risk                 VARCHAR(24)     NOT NULL,
    status               VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
    pending_guard        TINYINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED,
    requested_by         BIGINT UNSIGNED NOT NULL,
    decided_by           BIGINT UNSIGNED NULL,
    decision_comment     VARCHAR(512)    NULL,
    expires_at           DATETIME(6)     NOT NULL,
    decided_at           DATETIME(6)     NULL,
    execution_started_at DATETIME(6)     NULL,
    execution_finished_at DATETIME(6)    NULL,
    error_summary        VARCHAR(512)    NULL,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_pending_task (tenant_id, task_id, pending_guard),
    KEY idx_approval_tenant_status (tenant_id, status, created_at),
    KEY idx_approval_expiry (status, expires_at),
    CONSTRAINT ck_approval_status CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED',
        'EXECUTING', 'EXECUTED', 'FAILED'
    )),
    CONSTRAINT ck_approval_risk CHECK (risk = 'HIGH_RISK'),
    CONSTRAINT fk_approval_task_tenant
        FOREIGN KEY (task_id, tenant_id) REFERENCES agent_task (id, tenant_id),
    CONSTRAINT fk_approval_requester_tenant
        FOREIGN KEY (requested_by, tenant_id) REFERENCES user_account (id, tenant_id),
    CONSTRAINT fk_approval_decider_tenant
        FOREIGN KEY (decided_by, tenant_id) REFERENCES user_account (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
