ALTER TABLE student
    ADD UNIQUE KEY uk_student_school_id (school_id, id);

ALTER TABLE course_offering
    ADD UNIQUE KEY uk_offering_school_id (school_id, id);

ALTER TABLE enrollment
    ADD COLUMN school_id BIGINT UNSIGNED NULL AFTER id;

UPDATE enrollment e
JOIN course_offering o ON o.id = e.offering_id
SET e.school_id = o.school_id;

ALTER TABLE enrollment
    MODIFY COLUMN school_id BIGINT UNSIGNED NOT NULL,
    ADD COLUMN canceled_by BIGINT UNSIGNED NULL AFTER canceled_at,
    ADD UNIQUE KEY uk_enrollment_school_id (school_id, id),
    ADD CONSTRAINT fk_enrollment_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    ADD CONSTRAINT fk_enrollment_offering_school
        FOREIGN KEY (school_id, offering_id) REFERENCES course_offering (school_id, id),
    ADD CONSTRAINT fk_enrollment_student_school
        FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    ADD CONSTRAINT fk_enrollment_canceled_by_school
        FOREIGN KEY (school_id, canceled_by) REFERENCES sys_user (school_id, id);

ALTER TABLE lesson_session
    ADD COLUMN school_id BIGINT UNSIGNED NULL AFTER id;

UPDATE lesson_session ls
JOIN course_offering o ON o.id = ls.offering_id
SET ls.school_id = o.school_id;

ALTER TABLE lesson_session
    MODIFY COLUMN school_id BIGINT UNSIGNED NOT NULL,
    ADD UNIQUE KEY uk_session_school_id (school_id, id),
    ADD UNIQUE KEY uk_session_offering_id (offering_id, id),
    ADD CONSTRAINT fk_session_school
        FOREIGN KEY (school_id) REFERENCES school (id),
    ADD CONSTRAINT fk_session_offering_school
        FOREIGN KEY (school_id, offering_id) REFERENCES course_offering (school_id, id);

ALTER TABLE attendance
    ADD COLUMN school_id BIGINT UNSIGNED NULL AFTER id,
    ADD COLUMN offering_id BIGINT UNSIGNED NULL AFTER school_id;

UPDATE attendance a
JOIN lesson_session ls ON ls.id = a.session_id
SET a.school_id = ls.school_id,
    a.offering_id = ls.offering_id;

ALTER TABLE attendance
    MODIFY COLUMN school_id BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN offering_id BIGINT UNSIGNED NOT NULL,
    ADD CONSTRAINT fk_attendance_session_school
        FOREIGN KEY (school_id, session_id) REFERENCES lesson_session (school_id, id),
    ADD CONSTRAINT fk_attendance_student_school
        FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    ADD CONSTRAINT fk_attendance_recorded_by_school
        FOREIGN KEY (school_id, recorded_by) REFERENCES sys_user (school_id, id),
    ADD CONSTRAINT fk_attendance_session_offering
        FOREIGN KEY (offering_id, session_id) REFERENCES lesson_session (offering_id, id),
    ADD CONSTRAINT fk_attendance_enrollment
        FOREIGN KEY (offering_id, student_id) REFERENCES enrollment (offering_id, student_id);

CREATE INDEX idx_enrollment_school_status
    ON enrollment (school_id, status, offering_id);

CREATE INDEX idx_session_school_date
    ON lesson_session (school_id, session_date, status);

CREATE INDEX idx_attendance_school_status
    ON attendance (school_id, status, session_id);
