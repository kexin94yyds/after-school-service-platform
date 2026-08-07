ALTER TABLE student_guardian
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE AFTER is_primary;
