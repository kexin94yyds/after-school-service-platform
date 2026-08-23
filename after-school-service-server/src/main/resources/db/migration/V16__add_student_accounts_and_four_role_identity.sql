INSERT INTO sys_role (id, role_code, role_name, role_scope, enabled)
VALUES (5, 'STUDENT', '学生', 'SELF', TRUE);

UPDATE sys_role
SET role_name = '教务管理员'
WHERE role_code = 'SCHOOL_ADMIN';

UPDATE sys_role
SET enabled = FALSE
WHERE role_code = 'REGULATOR';

ALTER TABLE sys_user
    DROP CHECK ck_sys_user_role_school_scope,
    ADD CONSTRAINT ck_sys_user_role_school_scope CHECK (
        (role_id = 1 AND school_id IS NULL)
        OR
        (role_id IN (2, 3, 4, 5) AND school_id IS NOT NULL)
    );

ALTER TABLE student
    ADD COLUMN user_id BIGINT UNSIGNED NULL AFTER school_id,
    ADD UNIQUE KEY uk_student_user (user_id),
    ADD CONSTRAINT fk_student_user_school
        FOREIGN KEY (school_id, user_id) REFERENCES sys_user (school_id, id);

ALTER TABLE enrollment
    DROP FOREIGN KEY fk_enrollment_guardianship,
    MODIFY COLUMN guardian_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_enrollment_guardian
        FOREIGN KEY (guardian_id) REFERENCES guardian (id);

ALTER TABLE leave_request
    DROP FOREIGN KEY fk_leave_guardianship,
    MODIFY COLUMN guardian_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_leave_guardian
        FOREIGN KEY (guardian_id) REFERENCES guardian (id);

ALTER TABLE enrollment_action
    MODIFY COLUMN guardian_id BIGINT UNSIGNED NULL,
    DROP CHECK ck_enrollment_action_role,
    ADD CONSTRAINT ck_enrollment_action_role CHECK (
        actor_role IN ('SCHOOL_ADMIN', 'GUARDIAN', 'STUDENT')
    );

ALTER TABLE operation_audit
    DROP CHECK ck_operation_audit_actor_role,
    ADD CONSTRAINT ck_operation_audit_actor_role CHECK (
        actor_role IN (
            'REGULATOR', 'SCHOOL_ADMIN', 'TEACHER', 'GUARDIAN', 'STUDENT'
        )
    );
