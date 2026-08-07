ALTER TABLE enrollment
    ADD UNIQUE KEY uk_enrollment_school_offering_student (
        school_id, offering_id, student_id
    );

ALTER TABLE attendance
    ADD UNIQUE KEY uk_attendance_school_id (school_id, id);

CREATE TABLE leave_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    guardian_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_by BIGINT UNSIGNED NOT NULL,
    submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_by BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(3) NULL,
    review_remark VARCHAR(500) NULL,
    withdrawn_by BIGINT UNSIGNED NULL,
    withdrawn_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'APPROVED') THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_school_id (school_id, id),
    UNIQUE KEY uk_leave_active_session_student (
        session_id, student_id, active_marker
    ),
    KEY idx_leave_school_status (school_id, status, submitted_at),
    KEY idx_leave_offering_session (offering_id, session_id),
    KEY idx_leave_guardian_status (guardian_id, status),
    CONSTRAINT fk_leave_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_leave_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_leave_session_school
        FOREIGN KEY (school_id, session_id)
        REFERENCES lesson_session (school_id, id),
    CONSTRAINT fk_leave_session_offering
        FOREIGN KEY (offering_id, session_id)
        REFERENCES lesson_session (offering_id, id),
    CONSTRAINT fk_leave_student_school
        FOREIGN KEY (school_id, student_id)
        REFERENCES student (school_id, id),
    CONSTRAINT fk_leave_enrollment
        FOREIGN KEY (school_id, offering_id, student_id)
        REFERENCES enrollment (school_id, offering_id, student_id),
    CONSTRAINT fk_leave_guardianship
        FOREIGN KEY (student_id, guardian_id)
        REFERENCES student_guardian (student_id, guardian_id),
    CONSTRAINT fk_leave_submitted_by_school
        FOREIGN KEY (school_id, submitted_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_leave_reviewed_by_school
        FOREIGN KEY (school_id, reviewed_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_leave_withdrawn_by_school
        FOREIGN KEY (school_id, withdrawn_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_leave_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')
    ),
    CONSTRAINT ck_leave_state CHECK (
        (
            status = 'PENDING'
            AND reviewed_by IS NULL
            AND reviewed_at IS NULL
            AND withdrawn_by IS NULL
            AND withdrawn_at IS NULL
        )
        OR (
            status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL
            AND reviewed_at IS NOT NULL
            AND withdrawn_by IS NULL
            AND withdrawn_at IS NULL
        )
        OR (
            status = 'WITHDRAWN'
            AND reviewed_by IS NULL
            AND reviewed_at IS NULL
            AND withdrawn_by IS NOT NULL
            AND withdrawn_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_correction_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    attendance_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    requested_status VARCHAR(16) NOT NULL,
    requested_remark VARCHAR(255) NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT UNSIGNED NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_by BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(3) NULL,
    review_remark VARCHAR(500) NULL,
    applied_at DATETIME(3) NULL,
    canceled_by BIGINT UNSIGNED NULL,
    canceled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_correction_school_id (school_id, id),
    UNIQUE KEY uk_correction_active_attendance (
        attendance_id, active_marker
    ),
    KEY idx_correction_school_status (school_id, status, requested_at),
    KEY idx_correction_offering_session (offering_id, session_id),
    CONSTRAINT fk_correction_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_correction_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_correction_session_school
        FOREIGN KEY (school_id, session_id)
        REFERENCES lesson_session (school_id, id),
    CONSTRAINT fk_correction_session_offering
        FOREIGN KEY (offering_id, session_id)
        REFERENCES lesson_session (offering_id, id),
    CONSTRAINT fk_correction_attendance_school
        FOREIGN KEY (school_id, attendance_id)
        REFERENCES attendance (school_id, id),
    CONSTRAINT fk_correction_attendance_target
        FOREIGN KEY (session_id, student_id)
        REFERENCES attendance (session_id, student_id),
    CONSTRAINT fk_correction_student_school
        FOREIGN KEY (school_id, student_id)
        REFERENCES student (school_id, id),
    CONSTRAINT fk_correction_requested_by_school
        FOREIGN KEY (school_id, requested_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_correction_reviewed_by_school
        FOREIGN KEY (school_id, reviewed_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_correction_canceled_by_school
        FOREIGN KEY (school_id, canceled_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_correction_requested_status CHECK (
        requested_status IN ('PRESENT', 'LATE', 'LEAVE', 'ABSENT')
    ),
    CONSTRAINT ck_correction_status CHECK (
        status IN ('PENDING', 'REJECTED', 'CANCELED', 'APPLIED')
    ),
    CONSTRAINT ck_correction_state CHECK (
        (
            status = 'PENDING'
            AND reviewed_by IS NULL
            AND reviewed_at IS NULL
            AND applied_at IS NULL
            AND canceled_by IS NULL
            AND canceled_at IS NULL
        )
        OR (
            status = 'REJECTED'
            AND reviewed_by IS NOT NULL
            AND reviewed_at IS NOT NULL
            AND applied_at IS NULL
            AND canceled_by IS NULL
            AND canceled_at IS NULL
        )
        OR (
            status = 'CANCELED'
            AND reviewed_by IS NULL
            AND reviewed_at IS NULL
            AND applied_at IS NULL
            AND canceled_by IS NOT NULL
            AND canceled_at IS NOT NULL
        )
        OR (
            status = 'APPLIED'
            AND reviewed_by IS NOT NULL
            AND reviewed_at IS NOT NULL
            AND applied_at IS NOT NULL
            AND canceled_by IS NULL
            AND canceled_at IS NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_revision (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    attendance_id BIGINT UNSIGNED NOT NULL,
    correction_request_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    old_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    old_remark VARCHAR(255) NULL,
    new_remark VARCHAR(255) NULL,
    old_recorded_by BIGINT UNSIGNED NOT NULL,
    old_recorded_at DATETIME(3) NOT NULL,
    changed_by BIGINT UNSIGNED NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_revision_school_id (school_id, id),
    UNIQUE KEY uk_revision_correction (correction_request_id),
    KEY idx_revision_attendance_time (attendance_id, changed_at),
    KEY idx_revision_school_time (school_id, changed_at),
    CONSTRAINT fk_revision_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_revision_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_revision_session_school
        FOREIGN KEY (school_id, session_id)
        REFERENCES lesson_session (school_id, id),
    CONSTRAINT fk_revision_session_offering
        FOREIGN KEY (offering_id, session_id)
        REFERENCES lesson_session (offering_id, id),
    CONSTRAINT fk_revision_attendance_school
        FOREIGN KEY (school_id, attendance_id)
        REFERENCES attendance (school_id, id),
    CONSTRAINT fk_revision_attendance_target
        FOREIGN KEY (session_id, student_id)
        REFERENCES attendance (session_id, student_id),
    CONSTRAINT fk_revision_correction_school
        FOREIGN KEY (school_id, correction_request_id)
        REFERENCES attendance_correction_request (school_id, id),
    CONSTRAINT fk_revision_old_recorded_by_school
        FOREIGN KEY (school_id, old_recorded_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_revision_changed_by_school
        FOREIGN KEY (school_id, changed_by)
        REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_revision_old_status CHECK (
        old_status IN ('PRESENT', 'LATE', 'LEAVE', 'ABSENT')
    ),
    CONSTRAINT ck_revision_new_status CHECK (
        new_status IN ('PRESENT', 'LATE', 'LEAVE', 'ABSENT')
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only attendance history; application exposes INSERT and SELECT only';
