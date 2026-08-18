ALTER TABLE agent_task
    ADD COLUMN cancel_requested_at DATETIME(6) NULL AFTER error_summary,
    ADD COLUMN recovery_count INT UNSIGNED NOT NULL DEFAULT 0
        AFTER cancel_requested_at;

CREATE INDEX idx_agent_task_recovery
    ON agent_task (status, lease_until, cancel_requested_at);

ALTER TABLE approval_request
    DROP CHECK ck_approval_status,
    ADD CONSTRAINT ck_approval_status CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED',
        'EXECUTING', 'EXECUTED', 'FAILED'
    ));
