CREATE TABLE academic_term (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    term_code VARCHAR(32) NOT NULL,
    term_name VARCHAR(128) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_academic_term_code (term_code),
    KEY idx_academic_term_status_date (status, start_date, end_date),
    CONSTRAINT fk_academic_term_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT ck_academic_term_date CHECK (start_date <= end_date),
    CONSTRAINT ck_academic_term_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE school_service_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    term_id BIGINT UNSIGNED NOT NULL,
    plan_code VARCHAR(32) NOT NULL,
    plan_name VARCHAR(128) NOT NULL,
    description TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    return_reason VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    filed_at DATETIME(3) NULL,
    activated_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    archived_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    reviewed_by BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_plan_code (school_id, plan_code),
    UNIQUE KEY uk_service_plan_school_id (school_id, id),
    UNIQUE KEY uk_service_plan_school_term_id (school_id, term_id, id),
    KEY idx_service_plan_term_status (term_id, status, school_id),
    KEY idx_service_plan_reviewed_by (reviewed_by),
    CONSTRAINT fk_service_plan_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_service_plan_term
        FOREIGN KEY (term_id) REFERENCES academic_term (id),
    CONSTRAINT fk_service_plan_created_by_school
        FOREIGN KEY (school_id, created_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_service_plan_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT ck_service_plan_status
        CHECK (status IN (
            'DRAFT', 'SUBMITTED', 'FILED', 'RETURNED',
            'ACTIVE', 'CLOSED', 'ARCHIVED'
        )),
    CONSTRAINT ck_service_plan_return_reason
        CHECK (status <> 'RETURNED' OR return_reason IS NOT NULL),
    CONSTRAINT ck_service_plan_submitted_time
        CHECK (status = 'DRAFT' OR submitted_at IS NOT NULL),
    CONSTRAINT ck_service_plan_filed_time
        CHECK (status NOT IN ('FILED', 'ACTIVE', 'CLOSED', 'ARCHIVED')
               OR filed_at IS NOT NULL),
    CONSTRAINT ck_service_plan_activated_time
        CHECK (status NOT IN ('ACTIVE', 'CLOSED', 'ARCHIVED')
               OR activated_at IS NOT NULL),
    CONSTRAINT ck_service_plan_closed_time
        CHECK (status NOT IN ('CLOSED', 'ARCHIVED') OR closed_at IS NOT NULL),
    CONSTRAINT ck_service_plan_archived_time
        CHECK (status <> 'ARCHIVED' OR archived_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE school_room (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    room_code VARCHAR(32) NOT NULL,
    room_name VARCHAR(128) NOT NULL,
    location VARCHAR(255) NULL,
    capacity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_school_room_code (school_id, room_code),
    UNIQUE KEY uk_school_room_school_id (school_id, id),
    KEY idx_school_room_status (school_id, status, room_name),
    CONSTRAINT fk_school_room_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_school_room_created_by_school
        FOREIGN KEY (school_id, created_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_school_room_capacity CHECK (capacity > 0),
    CONSTRAINT ck_school_room_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE school_calendar_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    term_id BIGINT UNSIGNED NOT NULL,
    event_date DATE NOT NULL,
    day_type VARCHAR(24) NOT NULL,
    event_name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_calendar_school_date (school_id, event_date),
    UNIQUE KEY uk_calendar_school_id (school_id, id),
    KEY idx_calendar_term_date (term_id, event_date, school_id),
    CONSTRAINT fk_calendar_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_calendar_term
        FOREIGN KEY (term_id) REFERENCES academic_term (id),
    CONSTRAINT fk_calendar_created_by_school
        FOREIGN KEY (school_id, created_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_calendar_day_type
        CHECK (day_type IN (
            'TEACHING_DAY', 'MAKEUP_DAY', 'HOLIDAY', 'SUSPENDED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE course_offering
    ADD COLUMN term_id BIGINT UNSIGNED NULL AFTER term,
    ADD COLUMN plan_id BIGINT UNSIGNED NULL AFTER term_id,
    ADD COLUMN room_id BIGINT UNSIGNED NULL AFTER classroom,
    ADD KEY idx_offering_term_plan (term_id, plan_id, school_id),
    ADD KEY idx_offering_room_schedule (
        school_id, room_id, week_day, start_time, end_time
    ),
    ADD CONSTRAINT fk_offering_term
        FOREIGN KEY (term_id) REFERENCES academic_term (id),
    ADD CONSTRAINT fk_offering_plan_school_term
        FOREIGN KEY (school_id, term_id, plan_id)
        REFERENCES school_service_plan (school_id, term_id, id),
    ADD CONSTRAINT fk_offering_room_school
        FOREIGN KEY (school_id, room_id) REFERENCES school_room (school_id, id);

ALTER TABLE lesson_session
    ADD COLUMN room_id BIGINT UNSIGNED NULL AFTER classroom,
    ADD KEY idx_session_room_time (
        school_id, room_id, session_date, start_time, end_time
    ),
    ADD CONSTRAINT fk_session_room_school
        FOREIGN KEY (school_id, room_id) REFERENCES school_room (school_id, id);

CREATE TABLE schedule_adjustment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    original_session_date DATE NOT NULL,
    original_start_time TIME NOT NULL,
    original_end_time TIME NOT NULL,
    original_room_id BIGINT UNSIGNED NULL,
    original_classroom VARCHAR(64) NOT NULL,
    adjusted_session_date DATE NOT NULL,
    adjusted_start_time TIME NOT NULL,
    adjusted_end_time TIME NOT NULL,
    adjusted_room_id BIGINT UNSIGNED NOT NULL,
    adjusted_classroom VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    requested_by BIGINT UNSIGNED NOT NULL,
    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_adjustment_school_id (school_id, id),
    KEY idx_adjustment_session (session_id, applied_at),
    KEY idx_adjustment_school_date (
        school_id, adjusted_session_date, status
    ),
    CONSTRAINT fk_adjustment_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_adjustment_session_school
        FOREIGN KEY (school_id, session_id) REFERENCES lesson_session (school_id, id),
    CONSTRAINT fk_adjustment_original_room_school
        FOREIGN KEY (school_id, original_room_id) REFERENCES school_room (school_id, id),
    CONSTRAINT fk_adjustment_adjusted_room_school
        FOREIGN KEY (school_id, adjusted_room_id) REFERENCES school_room (school_id, id),
    CONSTRAINT fk_adjustment_requested_by_school
        FOREIGN KEY (school_id, requested_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_adjustment_original_time
        CHECK (original_start_time < original_end_time),
    CONSTRAINT ck_adjustment_adjusted_time
        CHECK (adjusted_start_time < adjusted_end_time),
    CONSTRAINT ck_adjustment_status
        CHECK (status IN ('APPLIED', 'REVERTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
