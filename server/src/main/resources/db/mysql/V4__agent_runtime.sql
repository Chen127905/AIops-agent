ALTER TABLE ticket
    ADD UNIQUE KEY uk_ticket_id_tenant (id, tenant_id);

CREATE TABLE agent_task
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT UNSIGNED NOT NULL,
    ticket_id           BIGINT UNSIGNED NOT NULL,
    requested_by        BIGINT UNSIGNED NOT NULL,
    status              VARCHAR(32)     NOT NULL DEFAULT 'QUEUED',
    active_guard        TINYINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL')
              THEN 1 ELSE NULL END) STORED,
    max_steps           INT UNSIGNED    NOT NULL,
    timeout_seconds     INT UNSIGNED    NOT NULL,
    max_tokens          INT UNSIGNED    NOT NULL,
    steps_used          INT UNSIGNED    NOT NULL DEFAULT 0,
    tokens_used         INT UNSIGNED    NOT NULL DEFAULT 0,
    next_event_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    worker_id           VARCHAR(128)    NULL,
    lease_until         DATETIME(6)     NULL,
    error_summary       VARCHAR(512)    NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at          DATETIME(6)     NULL,
    finished_at         DATETIME(6)     NULL,
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_id_tenant (id, tenant_id),
    UNIQUE KEY uk_agent_task_one_active (tenant_id, ticket_id, active_guard),
    KEY idx_agent_task_lease (status, lease_until),
    KEY idx_agent_task_tenant_created (tenant_id, created_at),
    CONSTRAINT ck_agent_task_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED',
        'FAILED', 'CANCELLED', 'TIMED_OUT', 'MANUAL_REQUIRED'
    )),
    CONSTRAINT ck_agent_task_budget CHECK (
        max_steps > 0 AND timeout_seconds > 0 AND max_tokens > 0
    ),
    CONSTRAINT fk_agent_task_ticket_tenant
        FOREIGN KEY (ticket_id, tenant_id) REFERENCES ticket (id, tenant_id),
    CONSTRAINT fk_agent_task_requester_tenant
        FOREIGN KEY (requested_by, tenant_id) REFERENCES user_account (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_step
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT UNSIGNED NOT NULL,
    task_id       BIGINT UNSIGNED NOT NULL,
    sequence      INT UNSIGNED    NOT NULL,
    node_name     VARCHAR(64)     NOT NULL,
    status        VARCHAR(24)     NOT NULL,
    input_data    JSON            NOT NULL,
    output_data   JSON            NOT NULL,
    error_summary VARCHAR(512)    NULL,
    duration_ms   BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_step_id_task_tenant (id, task_id, tenant_id),
    UNIQUE KEY uk_agent_step_task_sequence (task_id, sequence),
    KEY idx_agent_step_tenant_task (tenant_id, task_id, sequence),
    CONSTRAINT ck_agent_step_status CHECK (
        status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT fk_agent_step_task_tenant
        FOREIGN KEY (task_id, tenant_id) REFERENCES agent_task (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE model_invocation
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT UNSIGNED NOT NULL,
    task_id          BIGINT UNSIGNED NOT NULL,
    step_id          BIGINT UNSIGNED NULL,
    provider         VARCHAR(32)     NOT NULL,
    model_name       VARCHAR(128)    NOT NULL,
    request_hash     CHAR(64)        NOT NULL,
    status           VARCHAR(24)     NOT NULL,
    input_tokens     INT UNSIGNED    NOT NULL DEFAULT 0,
    output_tokens    INT UNSIGNED    NOT NULL DEFAULT 0,
    latency_ms       BIGINT UNSIGNED NOT NULL,
    error_summary    VARCHAR(512)    NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_model_invocation_task (tenant_id, task_id, created_at),
    CONSTRAINT ck_model_invocation_status CHECK (
        status IN ('SUCCEEDED', 'FAILED', 'TIMEOUT')
    ),
    CONSTRAINT fk_model_invocation_task_tenant
        FOREIGN KEY (task_id, tenant_id) REFERENCES agent_task (id, tenant_id),
    CONSTRAINT fk_model_invocation_step_task_tenant
        FOREIGN KEY (step_id, task_id, tenant_id)
            REFERENCES agent_step (id, task_id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE tool_invocation
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT UNSIGNED NOT NULL,
    task_id              BIGINT UNSIGNED NOT NULL,
    step_id              BIGINT UNSIGNED NULL,
    tool_name            VARCHAR(64)     NOT NULL,
    normalized_arguments JSON            NOT NULL,
    arguments_hash       CHAR(64)        NOT NULL,
    risk                 VARCHAR(24)     NOT NULL,
    status               VARCHAR(32)     NOT NULL,
    idempotency_key      VARCHAR(128)    NULL,
    latency_ms           BIGINT UNSIGNED NOT NULL,
    result_summary       JSON            NULL,
    error_summary        VARCHAR(512)    NULL,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_invocation_idempotency
        (tenant_id, task_id, tool_name, idempotency_key),
    KEY idx_tool_invocation_task (tenant_id, task_id, created_at),
    CONSTRAINT ck_tool_invocation_risk CHECK (
        risk IN ('READ_ONLY', 'HIGH_RISK')
    ),
    CONSTRAINT ck_tool_invocation_status CHECK (status IN (
        'SUCCESS', 'REJECTED', 'APPROVAL_REQUIRED', 'TIMEOUT', 'FAILED'
    )),
    CONSTRAINT fk_tool_invocation_task_tenant
        FOREIGN KEY (task_id, tenant_id) REFERENCES agent_task (id, tenant_id),
    CONSTRAINT fk_tool_invocation_step_task_tenant
        FOREIGN KEY (step_id, task_id, tenant_id)
            REFERENCES agent_step (id, task_id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_event
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT UNSIGNED NOT NULL,
    task_id    BIGINT UNSIGNED NOT NULL,
    sequence   BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(64)     NOT NULL,
    payload    JSON            NOT NULL,
    created_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_event_task_sequence (task_id, sequence),
    KEY idx_agent_event_replay (tenant_id, task_id, sequence),
    CONSTRAINT fk_agent_event_task_tenant
        FOREIGN KEY (task_id, tenant_id) REFERENCES agent_task (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
