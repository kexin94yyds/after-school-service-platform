CREATE TABLE service_plan_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    plan_id BIGINT UNSIGNED NOT NULL,
    category VARCHAR(64) NOT NULL,
    planned_course_count INT UNSIGNED NOT NULL,
    planned_class_count INT UNSIGNED NOT NULL,
    capacity_per_class INT UNSIGNED NOT NULL,
    planned_teacher_count INT UNSIGNED NOT NULL,
    notes VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_item_category (plan_id, category),
    UNIQUE KEY uk_plan_item_school_id (school_id, id),
    KEY idx_plan_item_scope (school_id, plan_id, category),
    CONSTRAINT fk_plan_item_plan_school
        FOREIGN KEY (school_id, plan_id)
        REFERENCES school_service_plan (school_id, id),
    CONSTRAINT fk_plan_item_creator_school
        FOREIGN KEY (school_id, created_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_plan_item_counts CHECK (
        planned_course_count > 0
        AND planned_class_count > 0
        AND capacity_per_class > 0
        AND planned_teacher_count > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Service-plan course category, class scale and staffing details';

CREATE TABLE regulator_school_scope (
    regulator_user_id BIGINT UNSIGNED NOT NULL,
    school_id BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (regulator_user_id, school_id),
    KEY idx_regulator_scope_school (school_id, regulator_user_id),
    CONSTRAINT fk_regulator_scope_user
        FOREIGN KEY (regulator_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_regulator_scope_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_regulator_scope_assigner
        FOREIGN KEY (assigned_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Schools whose alerts are delivered to each regulator account';

INSERT INTO regulator_school_scope (
    regulator_user_id,
    school_id,
    assigned_by
)
SELECT regulator.id, school.id, regulator.id
FROM sys_user regulator
JOIN sys_role role ON role.id = regulator.role_id
CROSS JOIN school
WHERE role.role_code = 'REGULATOR';

CREATE TABLE regulator_notification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    regulator_user_id BIGINT UNSIGNED NOT NULL,
    school_id BIGINT UNSIGNED NOT NULL,
    alert_id BIGINT UNSIGNED NOT NULL,
    notification_type VARCHAR(32) NOT NULL DEFAULT 'SUPERVISION_ALERT',
    title VARCHAR(128) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_regulator_notification_alert (
        regulator_user_id, alert_id, notification_type
    ),
    KEY idx_regulator_notification_inbox (
        regulator_user_id, is_read, created_at
    ),
    CONSTRAINT fk_regulator_notification_scope
        FOREIGN KEY (regulator_user_id, school_id)
        REFERENCES regulator_school_scope (regulator_user_id, school_id),
    CONSTRAINT fk_regulator_notification_alert_school
        FOREIGN KEY (school_id, alert_id)
        REFERENCES supervision_alert (school_id, id),
    CONSTRAINT ck_regulator_notification_type CHECK (
        notification_type IN ('SUPERVISION_ALERT', 'RECTIFICATION_SUBMITTED')
    ),
    CONSTRAINT ck_regulator_notification_read CHECK (
        (is_read = FALSE AND read_at IS NULL)
        OR (is_read = TRUE AND read_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='In-app alert delivery for assigned regulator accounts';

CREATE TABLE enrollment_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    enrollment_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    guardian_id BIGINT UNSIGNED NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    related_enrollment_id BIGINT UNSIGNED NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    acted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_enrollment_action_enrollment (enrollment_id, acted_at, id),
    KEY idx_enrollment_action_student (student_id, acted_at, id),
    KEY idx_enrollment_action_scope (school_id, acted_at, id),
    CONSTRAINT fk_enrollment_action_identity
        FOREIGN KEY (school_id, enrollment_id, offering_id, student_id)
        REFERENCES enrollment (school_id, id, offering_id, student_id),
    CONSTRAINT fk_enrollment_action_guardian
        FOREIGN KEY (guardian_id) REFERENCES guardian (id),
    CONSTRAINT fk_enrollment_action_actor
        FOREIGN KEY (actor_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_enrollment_action_related
        FOREIGN KEY (related_enrollment_id) REFERENCES enrollment (id),
    CONSTRAINT ck_enrollment_action_type CHECK (
        action_type IN (
            'ENROLL', 'CANCEL', 'REACTIVATE', 'SWITCH_OUT', 'SWITCH_IN'
        )
    ),
    CONSTRAINT ck_enrollment_action_role CHECK (
        actor_role IN ('SCHOOL_ADMIN', 'GUARDIAN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only enrollment and course-switch history';

INSERT INTO enrollment_action (
    school_id,
    enrollment_id,
    offering_id,
    student_id,
    guardian_id,
    action_type,
    actor_user_id,
    actor_role,
    acted_at
)
SELECT
    enrollment.school_id,
    enrollment.id,
    enrollment.offering_id,
    enrollment.student_id,
    enrollment.guardian_id,
    'ENROLL',
    guardian.user_id,
    'GUARDIAN',
    enrollment.enrolled_at
FROM enrollment
JOIN guardian ON guardian.id = enrollment.guardian_id;

INSERT INTO enrollment_action (
    school_id,
    enrollment_id,
    offering_id,
    student_id,
    guardian_id,
    action_type,
    actor_user_id,
    actor_role,
    acted_at
)
SELECT
    enrollment.school_id,
    enrollment.id,
    enrollment.offering_id,
    enrollment.student_id,
    enrollment.guardian_id,
    'CANCEL',
    enrollment.canceled_by,
    canceler_role.role_code,
    enrollment.canceled_at
FROM enrollment
JOIN sys_user canceler ON canceler.id = enrollment.canceled_by
JOIN sys_role canceler_role ON canceler_role.id = canceler.role_id
WHERE enrollment.status = 'CANCELED'
  AND enrollment.canceled_by IS NOT NULL
  AND canceler_role.role_code IN ('SCHOOL_ADMIN', 'GUARDIAN');

CREATE TABLE rectification_notice (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    alert_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(128) NOT NULL,
    requirements VARCHAR(2000) NOT NULL,
    due_at DATETIME(3) NOT NULL,
    issued_by BIGINT UNSIGNED NOT NULL,
    issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rectification_notice_alert (alert_id),
    UNIQUE KEY uk_rectification_notice_school_id (school_id, id),
    KEY idx_rectification_notice_due (school_id, due_at),
    CONSTRAINT fk_rectification_notice_alert_school
        FOREIGN KEY (school_id, alert_id)
        REFERENCES supervision_alert (school_id, id),
    CONSTRAINT fk_rectification_notice_issuer
        FOREIGN KEY (issued_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Regulator-issued rectification notice for one alert';

CREATE TABLE rectification_material (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    alert_id BIGINT UNSIGNED NOT NULL,
    notice_id BIGINT UNSIGNED NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(191) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    uploaded_by BIGINT UNSIGNED NOT NULL,
    uploaded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rectification_material_object (object_key),
    KEY idx_rectification_material_alert (alert_id, uploaded_at, id),
    CONSTRAINT fk_rectification_material_notice_school
        FOREIGN KEY (school_id, notice_id)
        REFERENCES rectification_notice (school_id, id),
    CONSTRAINT fk_rectification_material_alert_school
        FOREIGN KEY (school_id, alert_id)
        REFERENCES supervision_alert (school_id, id),
    CONSTRAINT fk_rectification_material_uploader
        FOREIGN KEY (uploaded_by) REFERENCES sys_user (id),
    CONSTRAINT ck_rectification_material_type CHECK (
        content_type IN ('application/pdf', 'image/jpeg', 'image/png')
    ),
    CONSTRAINT ck_rectification_material_size CHECK (
        size_bytes > 0 AND size_bytes <= 10485760
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Versioned evidence files submitted during rectification';

ALTER TABLE course_evaluation
    ADD COLUMN course_rating TINYINT UNSIGNED NULL AFTER guardian_id,
    ADD COLUMN teacher_rating TINYINT UNSIGNED NULL AFTER course_rating;

UPDATE course_evaluation
SET course_rating = rating,
    teacher_rating = rating
WHERE course_rating IS NULL OR teacher_rating IS NULL;

ALTER TABLE course_evaluation
    MODIFY COLUMN course_rating TINYINT UNSIGNED NOT NULL DEFAULT 5,
    MODIFY COLUMN teacher_rating TINYINT UNSIGNED NOT NULL DEFAULT 5,
    ADD KEY idx_course_evaluation_teacher_rating (
        school_id, teacher_rating, submitted_at
    ),
    ADD CONSTRAINT ck_course_evaluation_course_rating
        CHECK (course_rating BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_course_evaluation_teacher_rating
        CHECK (teacher_rating BETWEEN 1 AND 5);

ALTER TABLE supervision_alert
    DROP CHECK ck_supervision_alert_type,
    DROP CHECK ck_supervision_alert_subject;

ALTER TABLE supervision_alert_action
    DROP CHECK ck_supervision_action_type;

ALTER TABLE supervision_alert_action
    ADD CONSTRAINT ck_supervision_action_type CHECK (
        action_type IN (
            'CREATE',
            'ISSUE_NOTICE',
            'ACKNOWLEDGE',
            'START_RECTIFICATION',
            'RESUME_RECTIFICATION',
            'SUBMIT_MATERIAL',
            'SUBMIT_VERIFICATION',
            'VERIFY_CLOSE',
            'RETURN_FOR_RECTIFICATION'
        )
    );

ALTER TABLE supervision_alert
    ADD CONSTRAINT ck_supervision_alert_type CHECK (
        alert_type IN (
            'OVERDUE_ATTENDANCE',
            'OFFERING_NO_SESSIONS',
            'LOW_ATTENDANCE',
            'OVER_CAPACITY',
            'STAFF_SHORTAGE',
            'MISSING_ATTENDANCE',
            'UNFILED_OFFERING'
        )
    ),
    ADD CONSTRAINT ck_supervision_alert_subject CHECK (
        (alert_type IN ('OVERDUE_ATTENDANCE', 'MISSING_ATTENDANCE')
            AND session_id IS NOT NULL)
        OR
        (alert_type IN (
            'OFFERING_NO_SESSIONS',
            'LOW_ATTENDANCE',
            'OVER_CAPACITY',
            'STAFF_SHORTAGE',
            'UNFILED_OFFERING'
        ) AND session_id IS NULL)
    );
