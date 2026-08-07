ALTER TABLE enrollment
    ADD UNIQUE KEY uk_enrollment_school_identity_target (
        school_id, id, offering_id, student_id
    );

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_role_school_scope CHECK (
        (role_id = 1 AND school_id IS NULL)
        OR
        (role_id IN (2, 3, 4) AND school_id IS NOT NULL)
    );

CREATE TABLE supervision_alert (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NULL,
    alert_type VARCHAR(32) NOT NULL,
    dedup_key VARCHAR(191) NOT NULL,
    scan_run_id CHAR(36) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    title VARCHAR(128) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    metric_value DECIMAL(12, 4) NULL,
    threshold_value DECIMAL(12, 4) NULL,
    rectification_deadline DATETIME(3) NOT NULL,
    detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status <> 'CLOSED' THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_supervision_alert_dedup (
        school_id, alert_type, dedup_key, active_marker
    ),
    UNIQUE KEY uk_supervision_alert_school_id (school_id, id),
    KEY idx_supervision_alert_scope (
        school_id, status, alert_type, detected_at
    ),
    KEY idx_supervision_alert_deadline (status, rectification_deadline),
    CONSTRAINT fk_supervision_alert_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_supervision_alert_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_supervision_alert_session_school
        FOREIGN KEY (school_id, session_id)
        REFERENCES lesson_session (school_id, id),
    CONSTRAINT fk_supervision_alert_session_offering
        FOREIGN KEY (offering_id, session_id)
        REFERENCES lesson_session (offering_id, id),
    CONSTRAINT ck_supervision_alert_type CHECK (
        alert_type IN (
            'OVERDUE_ATTENDANCE',
            'OFFERING_NO_SESSIONS',
            'LOW_ATTENDANCE'
        )
    ),
    CONSTRAINT ck_supervision_alert_severity CHECK (
        severity IN ('LOW', 'MEDIUM', 'HIGH')
    ),
    CONSTRAINT ck_supervision_alert_status CHECK (
        status IN (
            'OPEN',
            'ACKNOWLEDGED',
            'RECTIFYING',
            'WAITING_VERIFY',
            'CLOSED',
            'RETURNED'
        )
    ),
    CONSTRAINT ck_supervision_alert_subject CHECK (
        (alert_type = 'OVERDUE_ATTENDANCE' AND session_id IS NOT NULL)
        OR
        (alert_type IN ('OFFERING_NO_SESSIONS', 'LOW_ATTENDANCE')
            AND session_id IS NULL)
    ),
    CONSTRAINT ck_supervision_alert_closed_time CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL)
        OR
        (status <> 'CLOSED' AND closed_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='监管预警主记录；状态变化必须同时追加动作历史';

CREATE TABLE supervision_alert_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    alert_id BIGINT UNSIGNED NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    comment VARCHAR(1000) NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    acted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_supervision_action_alert (alert_id, acted_at, id),
    KEY idx_supervision_action_scope (school_id, acted_at),
    CONSTRAINT fk_supervision_action_alert_school
        FOREIGN KEY (school_id, alert_id)
        REFERENCES supervision_alert (school_id, id),
    CONSTRAINT fk_supervision_action_actor
        FOREIGN KEY (actor_id) REFERENCES sys_user (id),
    CONSTRAINT ck_supervision_action_from_status CHECK (
        from_status IS NULL OR from_status IN (
            'OPEN',
            'ACKNOWLEDGED',
            'RECTIFYING',
            'WAITING_VERIFY',
            'CLOSED',
            'RETURNED'
        )
    ),
    CONSTRAINT ck_supervision_action_to_status CHECK (
        to_status IN (
            'OPEN',
            'ACKNOWLEDGED',
            'RECTIFYING',
            'WAITING_VERIFY',
            'CLOSED',
            'RETURNED'
        )
    ),
    CONSTRAINT ck_supervision_action_type CHECK (
        action_type IN (
            'CREATE',
            'ACKNOWLEDGE',
            'START_RECTIFICATION',
            'RESUME_RECTIFICATION',
            'SUBMIT_VERIFICATION',
            'VERIFY_CLOSE',
            'RETURN_FOR_RECTIFICATION'
        )
    ),
    CONSTRAINT ck_supervision_action_actor_role CHECK (
        actor_role IN ('REGULATOR', 'SCHOOL_ADMIN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only supervision action history';

CREATE TABLE operation_audit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    actor_school_id BIGINT UNSIGNED NULL,
    target_school_id BIGINT UNSIGNED NULL,
    http_method VARCHAR(8) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    response_status SMALLINT UNSIGNED NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_operation_audit_scope (target_school_id, occurred_at, id),
    KEY idx_operation_audit_actor (actor_user_id, occurred_at),
    KEY idx_operation_audit_method (http_method, occurred_at),
    CONSTRAINT fk_operation_audit_actor
        FOREIGN KEY (actor_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_operation_audit_actor_school
        FOREIGN KEY (actor_school_id, actor_user_id)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_operation_audit_target_school
        FOREIGN KEY (target_school_id) REFERENCES school (id),
    CONSTRAINT ck_operation_audit_method CHECK (
        http_method IN ('POST', 'PUT', 'PATCH', 'DELETE')
    ),
    CONSTRAINT ck_operation_audit_status CHECK (
        response_status BETWEEN 200 AND 299
    ),
    CONSTRAINT ck_operation_audit_actor_role CHECK (
        actor_role IN (
            'REGULATOR', 'SCHOOL_ADMIN', 'TEACHER', 'GUARDIAN'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only sanitized operation audit metadata';

CREATE TABLE course_evaluation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    enrollment_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    guardian_id BIGINT UNSIGNED NOT NULL,
    rating TINYINT UNSIGNED NOT NULL,
    comment VARCHAR(1000) NULL,
    submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_evaluation_target (offering_id, student_id),
    UNIQUE KEY uk_course_evaluation_school_id (school_id, id),
    KEY idx_course_evaluation_scope (school_id, rating, submitted_at),
    KEY idx_course_evaluation_guardian (guardian_id, submitted_at),
    CONSTRAINT fk_course_evaluation_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_course_evaluation_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_course_evaluation_enrollment_identity
        FOREIGN KEY (
            school_id, enrollment_id, offering_id, student_id
        )
        REFERENCES enrollment (
            school_id, id, offering_id, student_id
        ),
    CONSTRAINT fk_course_evaluation_student_school
        FOREIGN KEY (school_id, student_id)
        REFERENCES student (school_id, id),
    CONSTRAINT fk_course_evaluation_guardianship
        FOREIGN KEY (student_id, guardian_id)
        REFERENCES student_guardian (student_id, guardian_id),
    CONSTRAINT ck_course_evaluation_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='One-time append-only guardian course evaluation';
