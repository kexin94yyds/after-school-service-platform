-- Demo-only student accounts. The shared BCrypt value matches the existing
-- demo convenience password and is never loaded by production profiles.
INSERT INTO sys_user (
    id, school_id, role_id, username, password_hash, display_name, mobile
) VALUES
    (20, 1, 5, 'student_chen_he',
     '{bcrypt}$2y$10$z45mz6/iAGhpW/xaKaD5d.r6n1LEAAp.3JH4wcPHlxLcNiQUxRI0u',
     '陈小禾', NULL),
    (21, 1, 5, 'student_chen_shu',
     '{bcrypt}$2y$10$z45mz6/iAGhpW/xaKaD5d.r6n1LEAAp.3JH4wcPHlxLcNiQUxRI0u',
     '陈小树', NULL),
    (22, 2, 5, 'student_zhao_xinghe',
     '{bcrypt}$2y$10$z45mz6/iAGhpW/xaKaD5d.r6n1LEAAp.3JH4wcPHlxLcNiQUxRI0u',
     '赵星河', NULL);

UPDATE student
SET user_id = CASE id
    WHEN 1 THEN 20
    WHEN 2 THEN 21
    WHEN 3 THEN 22
END
WHERE id IN (1, 2, 3);
