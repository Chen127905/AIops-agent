ALTER TABLE ticket
    ADD COLUMN scenario_key VARCHAR(64) NULL AFTER category;

UPDATE ticket
SET scenario_key = category
WHERE category IN (
    'redis-timeout', 'db-pool-exhausted', 'api-error-rate',
    'mq-backlog', 'disk-full'
);

CREATE INDEX idx_ticket_tenant_scenario_created_at
    ON ticket (tenant_id, scenario_key, created_at);
