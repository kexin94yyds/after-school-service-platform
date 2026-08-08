ALTER TABLE supervision_alert_action
    MODIFY actor_id BIGINT UNSIGNED NULL;

ALTER TABLE supervision_alert_action
    DROP CHECK ck_supervision_action_actor_role;

ALTER TABLE supervision_alert_action
    ADD CONSTRAINT ck_supervision_action_actor_role CHECK (
        actor_role IN ('REGULATOR', 'SCHOOL_ADMIN', 'SYSTEM')
    ),
    ADD CONSTRAINT ck_supervision_action_actor_identity CHECK (
        (actor_role = 'SYSTEM' AND actor_id IS NULL)
        OR
        (actor_role IN ('REGULATOR', 'SCHOOL_ADMIN') AND actor_id IS NOT NULL)
    );

CREATE TABLE supervision_scan_run (
    id CHAR(36) NOT NULL,
    trigger_source VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    candidate_count INT UNSIGNED NULL,
    created_count INT UNSIGNED NULL,
    failure_summary VARCHAR(512) NULL,
    operator_user_id BIGINT UNSIGNED NULL,
    running_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'RUNNING' THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_supervision_scan_run_running (running_marker),
    KEY idx_supervision_scan_run_history (started_at, id),
    KEY idx_supervision_scan_run_status (status, started_at),
    CONSTRAINT fk_supervision_scan_run_operator
        FOREIGN KEY (operator_user_id) REFERENCES sys_user (id),
    CONSTRAINT ck_supervision_scan_run_source CHECK (
        trigger_source IN ('MANUAL', 'SCHEDULED')
    ),
    CONSTRAINT ck_supervision_scan_run_status CHECK (
        status IN ('RUNNING', 'SUCCESS', 'FAILED')
    ),
    CONSTRAINT ck_supervision_scan_run_actor CHECK (
        (trigger_source = 'MANUAL' AND operator_user_id IS NOT NULL)
        OR
        (trigger_source = 'SCHEDULED' AND operator_user_id IS NULL)
    ),
    CONSTRAINT ck_supervision_scan_run_completion CHECK (
        (status = 'RUNNING'
            AND finished_at IS NULL
            AND candidate_count IS NULL
            AND created_count IS NULL
            AND failure_summary IS NULL)
        OR
        (status = 'SUCCESS'
            AND finished_at IS NOT NULL
            AND candidate_count IS NOT NULL
            AND created_count IS NOT NULL
            AND failure_summary IS NULL)
        OR
        (status = 'FAILED'
            AND finished_at IS NOT NULL
            AND failure_summary IS NOT NULL)
    ),
    CONSTRAINT ck_supervision_scan_run_counts CHECK (
        candidate_count IS NULL
        OR created_count IS NULL
        OR created_count <= candidate_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='监管扫描运行留痕；终态记录只允许由运行收尾更新';
