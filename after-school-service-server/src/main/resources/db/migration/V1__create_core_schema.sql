CREATE TABLE sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    role_scope VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code),
    CONSTRAINT ck_sys_role_scope CHECK (role_scope IN ('REGULATOR', 'SCHOOL', 'SELF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE school (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_code VARCHAR(32) NOT NULL,
    school_name VARCHAR(128) NOT NULL,
    district_code VARCHAR(32) NOT NULL,
    address VARCHAR(255) NULL,
    contact_phone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_school_code (school_code),
    KEY idx_school_district (district_code),
    CONSTRAINT ck_school_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE school_class (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    class_name VARCHAR(64) NOT NULL,
    grade TINYINT UNSIGNED NOT NULL,
    school_year VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_school_class_name (school_id, school_year, grade, class_name),
    UNIQUE KEY uk_school_class_school_id (school_id, id),
    KEY idx_school_class_school (school_id),
    CONSTRAINT fk_school_class_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT ck_school_class_grade CHECK (grade BETWEEN 1 AND 12),
    CONSTRAINT ck_school_class_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    UNIQUE KEY uk_sys_user_school_id (school_id, id),
    KEY idx_sys_user_school (school_id),
    KEY idx_sys_user_role (role_id),
    CONSTRAINT fk_sys_user_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_sys_user_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE teacher (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    teacher_no VARCHAR(32) NOT NULL,
    full_name VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NULL,
    title VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_teacher_user (user_id),
    UNIQUE KEY uk_teacher_no (school_id, teacher_no),
    UNIQUE KEY uk_teacher_school_id (school_id, id),
    KEY idx_teacher_school (school_id),
    CONSTRAINT fk_teacher_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_teacher_user_school FOREIGN KEY (school_id, user_id) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_teacher_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE student (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    class_id BIGINT UNSIGNED NOT NULL,
    student_no VARCHAR(32) NOT NULL,
    full_name VARCHAR(64) NOT NULL,
    gender VARCHAR(16) NULL,
    date_of_birth DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_no (school_id, student_no),
    KEY idx_student_class (class_id),
    CONSTRAINT fk_student_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_student_class_school FOREIGN KEY (school_id, class_id) REFERENCES school_class (school_id, id),
    CONSTRAINT ck_student_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT ck_student_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE guardian (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    full_name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_guardian_user (user_id),
    KEY idx_guardian_mobile (mobile),
    CONSTRAINT fk_guardian_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT ck_guardian_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE student_guardian (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id BIGINT UNSIGNED NOT NULL,
    guardian_id BIGINT UNSIGNED NOT NULL,
    relationship VARCHAR(32) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_guardian (student_id, guardian_id),
    KEY idx_student_guardian_guardian (guardian_id),
    CONSTRAINT fk_student_guardian_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE CASCADE,
    CONSTRAINT fk_student_guardian_guardian FOREIGN KEY (guardian_id) REFERENCES guardian (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE course (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    course_code VARCHAR(32) NOT NULL,
    course_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    description TEXT NULL,
    target_grade_min TINYINT UNSIGNED NOT NULL,
    target_grade_max TINYINT UNSIGNED NOT NULL,
    default_capacity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_code (school_id, course_code),
    UNIQUE KEY uk_course_school_id (school_id, id),
    KEY idx_course_school_status (school_id, status),
    CONSTRAINT fk_course_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_course_created_by_school FOREIGN KEY (school_id, created_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_course_grade_min CHECK (target_grade_min BETWEEN 1 AND 12),
    CONSTRAINT ck_course_grade_max CHECK (target_grade_max BETWEEN 1 AND 12),
    CONSTRAINT ck_course_grade_range CHECK (target_grade_min <= target_grade_max),
    CONSTRAINT ck_course_capacity CHECK (default_capacity > 0),
    CONSTRAINT ck_course_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE course_offering (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    course_id BIGINT UNSIGNED NOT NULL,
    teacher_id BIGINT UNSIGNED NOT NULL,
    offering_code VARCHAR(32) NOT NULL,
    term VARCHAR(32) NOT NULL,
    week_day TINYINT UNSIGNED NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    enrollment_start DATETIME(3) NOT NULL,
    enrollment_end DATETIME(3) NOT NULL,
    capacity INT UNSIGNED NOT NULL,
    enrolled_count INT UNSIGNED NOT NULL DEFAULT 0,
    classroom VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_offering_code (school_id, offering_code),
    KEY idx_offering_course (course_id),
    KEY idx_offering_teacher_schedule (teacher_id, week_day, start_time, end_time),
    KEY idx_offering_school_status (school_id, status),
    CONSTRAINT fk_offering_school FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_offering_course_school FOREIGN KEY (school_id, course_id) REFERENCES course (school_id, id),
    CONSTRAINT fk_offering_teacher_school FOREIGN KEY (school_id, teacher_id) REFERENCES teacher (school_id, id),
    CONSTRAINT ck_offering_week_day CHECK (week_day BETWEEN 1 AND 7),
    CONSTRAINT ck_offering_time CHECK (start_time < end_time),
    CONSTRAINT ck_offering_date CHECK (start_date <= end_date),
    CONSTRAINT ck_offering_enrollment_time CHECK (enrollment_start < enrollment_end),
    CONSTRAINT ck_offering_capacity CHECK (capacity > 0 AND enrolled_count <= capacity),
    CONSTRAINT ck_offering_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'FINISHED', 'CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE enrollment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    offering_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    guardian_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENROLLED',
    enrolled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    canceled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_offering_student (offering_id, student_id),
    KEY idx_enrollment_student_status (student_id, status),
    KEY idx_enrollment_guardian (guardian_id),
    CONSTRAINT fk_enrollment_offering FOREIGN KEY (offering_id) REFERENCES course_offering (id),
    CONSTRAINT fk_enrollment_guardianship FOREIGN KEY (student_id, guardian_id)
        REFERENCES student_guardian (student_id, guardian_id),
    CONSTRAINT ck_enrollment_status CHECK (status IN ('ENROLLED', 'CANCELED')),
    CONSTRAINT ck_enrollment_cancel_time CHECK (
        (status = 'ENROLLED' AND canceled_at IS NULL)
        OR (status = 'CANCELED' AND canceled_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lesson_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    offering_id BIGINT UNSIGNED NOT NULL,
    session_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    classroom VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    notes VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_session_date (offering_id, session_date),
    KEY idx_lesson_session_date_status (session_date, status),
    CONSTRAINT fk_lesson_session_offering FOREIGN KEY (offering_id) REFERENCES course_offering (id),
    CONSTRAINT ck_lesson_session_time CHECK (start_time < end_time),
    CONSTRAINT ck_lesson_session_status CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    recorded_by BIGINT UNSIGNED NOT NULL,
    remark VARCHAR(255) NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_session_student (session_id, student_id),
    KEY idx_attendance_student_status (student_id, status),
    CONSTRAINT fk_attendance_session FOREIGN KEY (session_id) REFERENCES lesson_session (id),
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_attendance_recorded_by FOREIGN KEY (recorded_by) REFERENCES sys_user (id),
    CONSTRAINT ck_attendance_status CHECK (status IN ('PRESENT', 'LATE', 'LEAVE', 'ABSENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
