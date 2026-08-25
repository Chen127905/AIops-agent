CREATE TABLE ticket_conversation
(
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id                     BIGINT UNSIGNED NOT NULL,
    ticket_id                     BIGINT UNSIGNED NOT NULL,
    summary                       MEDIUMTEXT      NULL,
    summarized_through_message_id BIGINT UNSIGNED NULL,
    lease_owner                   VARCHAR(64)     NULL,
    lease_until                   DATETIME(6)     NULL,
    created_at                    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                                   ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_conversation_ticket (tenant_id, ticket_id),
    UNIQUE KEY uk_ticket_conversation_id_tenant (id, tenant_id),
    KEY idx_ticket_conversation_lease (lease_until),
    CONSTRAINT fk_ticket_conversation_ticket_tenant
        FOREIGN KEY (ticket_id, tenant_id) REFERENCES ticket (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ticket_conversation_message
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT UNSIGNED NOT NULL,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NULL,
    role            VARCHAR(16)     NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'SENT',
    content         MEDIUMTEXT      NOT NULL,
    provider        VARCHAR(32)     NULL,
    model_name      VARCHAR(128)    NULL,
    input_tokens    INT UNSIGNED    NOT NULL DEFAULT 0,
    output_tokens   INT UNSIGNED    NOT NULL DEFAULT 0,
    latency_ms      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ticket_conversation_message
        (tenant_id, conversation_id, id),
    CONSTRAINT ck_ticket_conversation_message_role
        CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_ticket_conversation_message_status
        CHECK (status IN ('SENT', 'FAILED')),
    CONSTRAINT fk_ticket_conversation_message_conversation_tenant
        FOREIGN KEY (conversation_id, tenant_id)
            REFERENCES ticket_conversation (id, tenant_id),
    CONSTRAINT fk_ticket_conversation_message_user_tenant
        FOREIGN KEY (user_id, tenant_id) REFERENCES user_account (id, tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
