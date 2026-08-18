CREATE TABLE managed_service
(
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT UNSIGNED NOT NULL,
    name                  VARCHAR(128)    NOT NULL,
    system_name           VARCHAR(128)    NOT NULL,
    environment           VARCHAR(32)     NOT NULL DEFAULT 'PRODUCTION',
    base_url              VARCHAR(512)    NOT NULL,
    health_path           VARCHAR(256)    NOT NULL DEFAULT '/actuator/health',
    metrics_path          VARCHAR(256)    NULL,
    logs_path             VARCHAR(256)    NULL,
    dependencies_path     VARCHAR(256)    NULL,
    operations_path       VARCHAR(256)    NULL,
    bearer_token_env      VARCHAR(128)    NULL,
    enabled               BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_managed_service_tenant_name (tenant_id, name),
    KEY idx_managed_service_tenant_system (tenant_id, system_name),
    CONSTRAINT fk_managed_service_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
