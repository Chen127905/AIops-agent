CREATE TABLE evaluation_run
(
    id                CHAR(36)        NOT NULL,
    tenant_id         BIGINT UNSIGNED NOT NULL,
    requested_by      BIGINT UNSIGNED NOT NULL,
    mode              VARCHAR(16)     NOT NULL,
    provider          VARCHAR(24)     NOT NULL,
    model_name        VARCHAR(128)    NOT NULL,
    prompt_version    VARCHAR(64)     NOT NULL,
    knowledge_version VARCHAR(64)     NOT NULL,
    status            VARCHAR(24)     NOT NULL,
    total_cases       INT UNSIGNED    NOT NULL,
    passed_cases      INT UNSIGNED    NOT NULL DEFAULT 0,
    metrics           JSON            NULL,
    started_at        DATETIME(6)     NOT NULL,
    finished_at       DATETIME(6)     NULL,
    PRIMARY KEY (id),
    KEY idx_evaluation_run_tenant_time (tenant_id, started_at),
    CONSTRAINT ck_evaluation_run_mode CHECK (mode IN ('MOCK', 'LIVE')),
    CONSTRAINT ck_evaluation_run_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'COMPLETED_WITH_FAILURES', 'FAILED')),
    CONSTRAINT fk_evaluation_run_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_evaluation_run_user_tenant
        FOREIGN KEY (requested_by, tenant_id) REFERENCES user_account (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE evaluation_case_result
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id           CHAR(36)        NOT NULL,
    tenant_id        BIGINT UNSIGNED NOT NULL,
    case_id          VARCHAR(128)    NOT NULL,
    case_group       VARCHAR(32)     NOT NULL,
    passed           BOOLEAN         NOT NULL,
    scores           JSON            NOT NULL,
    observation      JSON            NOT NULL,
    failure_category VARCHAR(64)     NULL,
    started_at       DATETIME(6)     NOT NULL,
    finished_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evaluation_case_run (run_id, case_id),
    KEY idx_evaluation_case_tenant_run (tenant_id, run_id),
    CONSTRAINT ck_evaluation_case_group CHECK (case_group IN (
        'CLASSIFICATION', 'RETRIEVAL', 'TOOL_USE',
        'END_TO_END', 'APPROVAL', 'ATTACK'
    )),
    CONSTRAINT fk_evaluation_case_run
        FOREIGN KEY (run_id) REFERENCES evaluation_run (id),
    CONSTRAINT fk_evaluation_case_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
