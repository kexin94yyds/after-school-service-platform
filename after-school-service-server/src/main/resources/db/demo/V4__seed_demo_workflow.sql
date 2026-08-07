-- All names, phones and identifiers in this migration are fictional demo data.
-- The shared demo password is stored only as a BCrypt hash.

INSERT INTO school (
    id, school_code, school_name, district_code, address, contact_phone
) VALUES
    (1, 'DEMO001', '示范实验学校', 'DEMO-DISTRICT',
     '示范区育才路 1 号', '010-00000000'),
    (2, 'DEMO002', '启航外国语学校', 'DEMO-DISTRICT',
     '示范区知行路 8 号', '010-00000001');

INSERT INTO school_class (
    id, school_id, class_name, grade, school_year
) VALUES
    (1, 1, '三年级一班', 3, '2026-2027'),
    (2, 1, '四年级二班', 4, '2026-2027'),
    (3, 2, '三年级二班', 3, '2026-2027');

INSERT INTO course (
    id, school_id, course_code, course_name, category, description,
    target_grade_min, target_grade_max, default_capacity, status
) VALUES (
    1, 1, 'DEMO-ART-001', '创意美术', '艺术素养',
    '用于初始化界面和查询联调的虚构课程，不对应真实学校或学生。',
    1, 6, 30, 'ACTIVE'
);

INSERT INTO sys_user (
    id, school_id, role_id, username, password_hash, display_name, mobile
) VALUES
    (10, NULL, 1, 'regulator',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '示范区监管员', '13800000010'),
    (11, 1, 2, 'school_admin',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '实验学校管理员', '13800000011'),
    (12, 1, 3, 'teacher_wang',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '王老师', '13800000012'),
    (13, 1, 4, 'parent_chen',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '陈家长', '13800000013'),
    (14, 2, 2, 'school_admin_2',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '启航学校管理员', '13800000014'),
    (15, 2, 3, 'teacher_li',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '李老师', '13800000015'),
    (16, 2, 4, 'parent_zhao',
     '{bcrypt}$2y$10$Rh7woH4ZXop4zXDis38GH.02em6W0AFXtjRKNCUWbxhol8ImvtrOG',
     '赵家长', '13800000016');

INSERT INTO teacher (
    id, school_id, user_id, teacher_no, full_name, phone, title
) VALUES
    (1, 1, 12, 'T-DEMO-001', '王老师', '13800000012', '美术教师'),
    (2, 2, 15, 'T-DEMO-002', '李老师', '13800000015', '科学教师');

INSERT INTO student (
    id, school_id, class_id, student_no, full_name, gender, date_of_birth
) VALUES
    (1, 1, 1, 'S-DEMO-001', '陈小禾', 'FEMALE', '2017-05-12'),
    (2, 1, 2, 'S-DEMO-002', '陈小树', 'MALE', '2016-09-21'),
    (3, 2, 3, 'S-DEMO-003', '赵星河', 'MALE', '2017-03-08');

INSERT INTO guardian (
    id, user_id, full_name, mobile
) VALUES
    (1, 13, '陈家长', '13800000013'),
    (2, 16, '赵家长', '13800000016');

INSERT INTO student_guardian (
    id, student_id, guardian_id, relationship, is_primary
) VALUES
    (1, 1, 1, '母亲', TRUE),
    (2, 2, 1, '母亲', TRUE),
    (3, 3, 2, '父亲', TRUE);

INSERT INTO course (
    id, school_id, course_code, course_name, category, description,
    target_grade_min, target_grade_max, default_capacity, status, created_by
) VALUES
    (2, 1, 'DEMO-TECH-001', '趣味编程', '科技创新',
     '通过图形化任务培养逻辑思维的虚构演示课程。', 3, 6, 20, 'ACTIVE', 11),
    (3, 2, 'DEMO-SCI-001', '科学小实验', '科技创新',
     '面向低年级的安全科学实验演示课程。', 1, 4, 24, 'ACTIVE', 14);

INSERT INTO course_offering (
    id, school_id, course_id, teacher_id, offering_code, term,
    week_day, start_time, end_time, start_date, end_date,
    enrollment_start, enrollment_end, capacity, enrolled_count, classroom, status
) VALUES
    (1, 1, 1, 1, 'O-DEMO-ART-001', '2026-2027-1',
     2, '16:30:00', '17:30:00', '2026-09-01', '2027-01-31',
     '2020-01-01 00:00:00', '2099-12-31 23:59:59', 2, 1, '美术教室', 'PUBLISHED'),
    (2, 1, 2, 1, 'O-DEMO-TECH-001', '2026-2027-1',
     4, '16:30:00', '17:30:00', '2026-09-01', '2027-01-31',
     '2020-01-01 00:00:00', '2099-12-31 23:59:59', 20, 0, '信息教室', 'PUBLISHED'),
    (3, 2, 3, 2, 'O-DEMO-SCI-001', '2026-2027-1',
     3, '16:30:00', '17:30:00', '2026-09-01', '2027-01-31',
     '2020-01-01 00:00:00', '2099-12-31 23:59:59', 24, 0, '科学教室', 'PUBLISHED');

INSERT INTO enrollment (
    id, school_id, offering_id, student_id, guardian_id, status
) VALUES (
    1, 1, 1, 2, 1, 'ENROLLED'
);
