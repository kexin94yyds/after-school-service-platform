CREATE TABLE student_grade (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    offering_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    teacher_id BIGINT UNSIGNED NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    learning_evaluation VARCHAR(1000) NULL,
    recorded_by BIGINT UNSIGNED NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT UNSIGNED NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_grade_target (offering_id, student_id),
    UNIQUE KEY uk_student_grade_school_id (school_id, id),
    KEY idx_student_grade_student (student_id, updated_at),
    KEY idx_student_grade_teacher (teacher_id, updated_at),
    CONSTRAINT fk_student_grade_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    CONSTRAINT fk_student_grade_offering_school
        FOREIGN KEY (school_id, offering_id)
        REFERENCES course_offering (school_id, id),
    CONSTRAINT fk_student_grade_student_school
        FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT fk_student_grade_teacher_school
        FOREIGN KEY (school_id, teacher_id) REFERENCES teacher (school_id, id),
    CONSTRAINT fk_student_grade_enrollment
        FOREIGN KEY (school_id, offering_id, student_id)
        REFERENCES enrollment (school_id, offering_id, student_id),
    CONSTRAINT fk_student_grade_recorded_by
        FOREIGN KEY (school_id, recorded_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT fk_student_grade_updated_by
        FOREIGN KEY (school_id, updated_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_student_grade_score CHECK (score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Current 0-100 course grade and teacher learning evaluation';

CREATE TABLE student_grade_revision (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    school_id BIGINT UNSIGNED NOT NULL,
    grade_id BIGINT UNSIGNED NOT NULL,
    old_score DECIMAL(5,2) NOT NULL,
    new_score DECIMAL(5,2) NOT NULL,
    old_learning_evaluation VARCHAR(1000) NULL,
    new_learning_evaluation VARCHAR(1000) NULL,
    changed_by BIGINT UNSIGNED NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_grade_revision_grade (grade_id, changed_at, id),
    KEY idx_grade_revision_school (school_id, changed_at, id),
    CONSTRAINT fk_grade_revision_grade
        FOREIGN KEY (school_id, grade_id)
        REFERENCES student_grade (school_id, id),
    CONSTRAINT fk_grade_revision_changed_by
        FOREIGN KEY (school_id, changed_by) REFERENCES sys_user (school_id, id),
    CONSTRAINT ck_grade_revision_old_score CHECK (old_score BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_revision_new_score CHECK (new_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only student grade modification history';
