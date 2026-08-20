INSERT INTO academic_term (
    term_code,
    term_name,
    start_date,
    end_date,
    status,
    created_by
)
SELECT
    'DEMO-OPENING-REPORT',
    '开题报告完整业务演示学期',
    MIN(offering.start_date),
    MAX(offering.end_date),
    'ACTIVE',
    regulator.id
FROM course_offering offering
CROSS JOIN sys_user regulator
JOIN sys_role role
  ON role.id = regulator.role_id
 AND role.role_code = 'REGULATOR'
WHERE NOT EXISTS (
    SELECT 1 FROM academic_term WHERE term_code = 'DEMO-OPENING-REPORT'
)
GROUP BY regulator.id
ORDER BY regulator.id
LIMIT 1;

INSERT INTO school_service_plan (
    school_id,
    term_id,
    plan_code,
    plan_name,
    description,
    status,
    submitted_at,
    filed_at,
    activated_at,
    created_by,
    reviewed_by
)
SELECT
    school.id,
    term.id,
    CONCAT('DEMO-PLAN-', school.id),
    CONCAT(school.school_name, '完整课后服务计划'),
    '覆盖课程类型、开班规模和师资配置的演示备案计划',
    'ACTIVE',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3),
    school_admin.id,
    regulator.id
FROM school
JOIN academic_term term
  ON term.term_code = 'DEMO-OPENING-REPORT'
JOIN sys_user school_admin
  ON school_admin.school_id = school.id
JOIN sys_role school_admin_role
  ON school_admin_role.id = school_admin.role_id
 AND school_admin_role.role_code = 'SCHOOL_ADMIN'
 CROSS JOIN sys_user regulator
JOIN sys_role regulator_role
  ON regulator_role.id = regulator.role_id
 AND regulator_role.role_code = 'REGULATOR'
WHERE EXISTS (
    SELECT 1
    FROM course_offering offering
    WHERE offering.school_id = school.id
      AND (offering.term_id IS NULL OR offering.plan_id IS NULL)
)
  AND NOT EXISTS (
    SELECT 1
    FROM school_service_plan plan
    WHERE plan.school_id = school.id
      AND plan.plan_code = CONCAT('DEMO-PLAN-', school.id)
)
GROUP BY school.id, term.id, school_admin.id, regulator.id
ORDER BY school.id, school_admin.id, regulator.id;

INSERT INTO school_room (
    school_id,
    room_code,
    room_name,
    location,
    capacity,
    status,
    created_by
)
SELECT
    school.id,
    'DEMO-GENERAL',
    '演示综合教室',
    '演示教学楼',
    GREATEST(MAX(offering.capacity), 50),
    'ACTIVE',
    school_admin.id
FROM school
JOIN course_offering offering ON offering.school_id = school.id
JOIN sys_user school_admin ON school_admin.school_id = school.id
JOIN sys_role role
  ON role.id = school_admin.role_id
 AND role.role_code = 'SCHOOL_ADMIN'
WHERE NOT EXISTS (
    SELECT 1
    FROM school_room room
    WHERE room.school_id = school.id
      AND room.room_code = 'DEMO-GENERAL'
)
GROUP BY school.id, school_admin.id;

UPDATE course_offering offering
JOIN academic_term term
  ON term.term_code = 'DEMO-OPENING-REPORT'
JOIN school_service_plan plan
  ON plan.school_id = offering.school_id
 AND plan.plan_code = CONCAT('DEMO-PLAN-', offering.school_id)
JOIN school_room room
  ON room.school_id = offering.school_id
 AND room.room_code = 'DEMO-GENERAL'
SET offering.term_id = COALESCE(offering.term_id, term.id),
    offering.plan_id = COALESCE(offering.plan_id, plan.id),
    offering.room_id = COALESCE(offering.room_id, room.id),
    offering.classroom = room.room_name
WHERE offering.term_id IS NULL
   OR offering.plan_id IS NULL
   OR offering.room_id IS NULL;

UPDATE lesson_session session
JOIN course_offering offering ON offering.id = session.offering_id
JOIN school_room room ON room.id = offering.room_id
SET session.room_id = room.id,
    session.classroom = room.room_name
WHERE session.room_id IS NULL;

INSERT INTO service_plan_item (
    school_id,
    plan_id,
    category,
    planned_course_count,
    planned_class_count,
    capacity_per_class,
    planned_teacher_count,
    notes,
    created_by
)
SELECT
    plan.school_id,
    plan.id,
    course.category,
    COUNT(DISTINCT course.id),
    COUNT(DISTINCT offering.id),
    MAX(offering.capacity),
    COUNT(DISTINCT offering.teacher_id),
    '由演示开班数据生成的备案明细',
    plan.created_by
FROM school_service_plan plan
JOIN course_offering offering ON offering.plan_id = plan.id
JOIN course ON course.id = offering.course_id
LEFT JOIN service_plan_item existing
  ON existing.plan_id = plan.id
 AND existing.category = course.category
WHERE existing.id IS NULL
GROUP BY plan.school_id, plan.id, course.category, plan.created_by;

INSERT IGNORE INTO regulator_school_scope (
    regulator_user_id,
    school_id,
    assigned_by
)
SELECT regulator.id, school.id, regulator.id
FROM sys_user regulator
JOIN sys_role role
  ON role.id = regulator.role_id
 AND role.role_code = 'REGULATOR'
CROSS JOIN school;

UPDATE course_evaluation
SET course_rating = rating,
    teacher_rating = rating;
