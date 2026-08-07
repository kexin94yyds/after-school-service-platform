-- Comprehensive, fully fictional workflow data for the demo profile only.
-- This migration intentionally comes after the production V5-V7 feature schema.

INSERT INTO academic_term (
    id, term_code, term_name, start_date, end_date, status, created_by
) VALUES
    (1, '2026-2027-1', '2026—2027 学年第一学期',
     '2026-07-01', '2027-01-31', 'ACTIVE', 10),
    (2, '2025-2026-2', '2025—2026 学年第二学期',
     '2026-02-01', '2026-06-30', 'CLOSED', 10);

INSERT INTO school_service_plan (
    id, school_id, term_id, plan_code, plan_name, description, status,
    submitted_at, filed_at, activated_at, closed_at,
    created_by, reviewed_by
) VALUES
    (1, 1, 1, 'PLAN-DEMO001-2026-1', '示范实验学校第一学期课后服务计划',
     '覆盖艺术、科技与综合实践类课程，包含师资、场地和安全保障安排。',
     'ACTIVE',
     '2026-06-10 09:00:00.000', '2026-06-12 10:30:00.000',
     '2026-07-01 08:00:00.000', NULL, 11, 10),
    (2, 2, 1, 'PLAN-DEMO002-2026-1', '启航外国语学校第一学期课后服务计划',
     '以科学探究和语言实践为特色的课后服务实施计划。',
     'FILED',
     '2026-06-11 09:20:00.000', '2026-06-13 14:00:00.000',
     NULL, NULL, 14, 10),
    (3, 1, 2, 'PLAN-DEMO001-2025-2', '示范实验学校第二学期课后服务计划',
     '上一学期已完成的课后服务计划，用于展示历史课次与课程评价。',
     'CLOSED',
     '2026-01-10 09:00:00.000', '2026-01-12 10:00:00.000',
     '2026-02-01 08:00:00.000', '2026-06-30 18:00:00.000',
     11, 10);

INSERT INTO school_room (
    id, school_id, room_code, room_name, location, capacity, status, created_by
) VALUES
    (1, 1, 'ROOM-ART', '创意美术教室', '艺术楼 2 层 201', 30, 'ACTIVE', 11),
    (2, 1, 'ROOM-IT', '数字创客教室', '综合楼 3 层 305', 24, 'ACTIVE', 11),
    (3, 1, 'ROOM-OLD', '旧多功能教室', '综合楼 1 层 106', 20, 'INACTIVE', 11),
    (4, 2, 'ROOM-SCI', '科学探究室', '实验楼 2 层 208', 28, 'ACTIVE', 14);

INSERT INTO school_calendar_event (
    id, school_id, term_id, event_date, day_type, event_name, description,
    created_by
) VALUES
    (1, 1, 1, '2026-09-03', 'TEACHING_DAY', '课后服务正式开课',
     '第一学期课后服务统一启动日。', 11),
    (2, 1, 1, '2026-10-01', 'HOLIDAY', '国庆节停课',
     '法定节假日，所有课后服务课程暂停。', 11),
    (3, 2, 1, '2026-10-01', 'HOLIDAY', '国庆节停课',
     '法定节假日，所有课后服务课程暂停。', 14);

UPDATE course_offering
SET term_id = 1,
    plan_id = 1,
    room_id = 1,
    classroom = '创意美术教室'
WHERE id = 1;

UPDATE course_offering
SET term_id = 1,
    plan_id = 1,
    room_id = 2,
    classroom = '数字创客教室'
WHERE id = 2;

UPDATE course_offering
SET term_id = 1,
    plan_id = 2,
    room_id = 4,
    classroom = '科学探究室'
WHERE id = 3;

INSERT INTO course_offering (
    id, school_id, course_id, teacher_id, offering_code, term,
    term_id, plan_id, week_day, start_time, end_time, start_date, end_date,
    enrollment_start, enrollment_end, capacity, enrolled_count,
    classroom, room_id, status
) VALUES
    (4, 1, 2, 1, 'O-DEMO-TECH-HISTORY', '2025-2026-2',
     2, 3, 4, '15:30:00', '16:20:00', '2026-03-05', '2026-06-30',
     '2026-01-15 00:00:00.000', '2026-03-01 23:59:59.000',
     20, 1, '数字创客教室', 2, 'FINISHED'),
    (5, 1, 1, 1, 'O-DEMO-ART-HISTORY', '2025-2026-2',
     2, 3, 2, '16:30:00', '17:30:00', '2026-02-01', '2026-06-30',
     '2026-01-15 00:00:00.000', '2026-01-31 23:59:59.000',
     30, 1, '创意美术教室', 1, 'FINISHED');

UPDATE enrollment
SET enrolled_at = '2026-07-01 09:00:00.000'
WHERE id = 1;

INSERT INTO enrollment (
    id, school_id, offering_id, student_id, guardian_id, status, enrolled_at
) VALUES
    (2, 1, 4, 1, 1, 'ENROLLED', '2026-02-20 10:00:00.000'),
    (3, 1, 5, 2, 1, 'ENROLLED', '2026-01-20 10:05:00.000');

INSERT INTO lesson_session (
    id, school_id, offering_id, session_date, start_time, end_time,
    classroom, room_id, status, notes
) VALUES
    (1, 1, 5, '2026-06-16', '16:30:00', '17:30:00',
     '创意美术教室', 1, 'COMPLETED', '完成色彩基础主题教学。'),
    (2, 1, 1, '2026-09-08', '16:30:00', '17:30:00',
     '创意美术教室', 1, 'SCHEDULED', '水彩构图主题。'),
    (3, 1, 1, '2026-09-15', '16:30:00', '17:30:00',
     '数字创客教室', 2, 'SCHEDULED', '因场地维护已完成调课。'),
    (4, 1, 4, '2026-06-18', '15:30:00', '16:20:00',
     '数字创客教室', 2, 'COMPLETED', '完成结课作品展示。');

INSERT INTO attendance (
    id, school_id, offering_id, session_id, student_id, status,
    recorded_by, remark, recorded_at
) VALUES
    (1, 1, 5, 1, 2, 'ABSENT', 12, '原始记录：未到课',
     '2026-06-16 17:35:00.000'),
    (2, 1, 4, 4, 1, 'PRESENT', 12, '按时到课并完成作品',
     '2026-06-18 16:25:00.000');

INSERT INTO leave_request (
    id, school_id, offering_id, session_id, student_id, guardian_id,
    reason, status, submitted_by, submitted_at,
    reviewed_by, reviewed_at, review_remark
) VALUES (
    1, 1, 1, 2, 2, 1,
    '当天需参加校外复诊，申请事假。', 'APPROVED', 13,
    '2026-07-28 19:20:00.000', 12,
    '2026-07-29 08:30:00.000', '材料齐全，同意请假。'
);

INSERT INTO attendance_correction_request (
    id, school_id, offering_id, session_id, attendance_id, student_id,
    requested_status, requested_remark, reason, status,
    requested_by, requested_at
) VALUES (
    1, 1, 5, 1, 1, 2,
    'PRESENT', '补核签到记录：学生实际按时到课',
    '教师复核纸质签到表后发现原记录有误。', 'PENDING',
    12, '2026-06-17 09:10:00.000'
);

INSERT INTO schedule_adjustment (
    id, school_id, session_id,
    original_session_date, original_start_time, original_end_time,
    original_room_id, original_classroom,
    adjusted_session_date, adjusted_start_time, adjusted_end_time,
    adjusted_room_id, adjusted_classroom, reason, status,
    requested_by, applied_at
) VALUES (
    1, 1, 3,
    '2026-09-22', '16:30:00', '17:30:00', 1, '创意美术教室',
    '2026-09-15', '16:30:00', '17:30:00', 2, '数字创客教室',
    '原教室设备维护，提前一周并调整至数字创客教室。', 'APPLIED',
    11, '2026-07-30 10:00:00.000'
);

INSERT INTO supervision_alert (
    id, school_id, offering_id, session_id, alert_type, dedup_key,
    scan_run_id, severity, status, title, description,
    metric_value, threshold_value, rectification_deadline, detected_at
) VALUES (
    1, 1, 5, NULL, 'LOW_ATTENDANCE', 'OFFERING:5',
    '11111111-1111-4111-8111-111111111111',
    'MEDIUM', 'WAITING_VERIFY', '课程总体出勤率偏低',
    '系统发现该开班已完成课次的出勤率低于监管阈值，学校已提交核查说明。',
    0.0000, 0.8000, '2026-08-05 18:00:00.000',
    '2026-07-22 08:00:00.000'
);

INSERT INTO supervision_alert_action (
    id, school_id, alert_id, from_status, to_status, action_type,
    comment, actor_id, actor_role, acted_at
) VALUES
    (1, 1, 1, NULL, 'OPEN', 'CREATE',
     '监管扫描生成预警。', 10, 'REGULATOR', '2026-07-22 08:00:00.000'),
    (2, 1, 1, 'OPEN', 'ACKNOWLEDGED', 'ACKNOWLEDGE',
     '学校已接收预警并安排教务人员核查。', 11, 'SCHOOL_ADMIN',
     '2026-07-22 09:00:00.000'),
    (3, 1, 1, 'ACKNOWLEDGED', 'RECTIFYING', 'START_RECTIFICATION',
     '正在核对纸质签到表和家校沟通记录。', 11, 'SCHOOL_ADMIN',
     '2026-07-22 10:00:00.000'),
    (4, 1, 1, 'RECTIFYING', 'WAITING_VERIFY', 'SUBMIT_VERIFICATION',
     '已发起考勤纠错申请并补充佐证，请监管复核。', 11, 'SCHOOL_ADMIN',
     '2026-07-23 15:00:00.000');

INSERT INTO operation_audit (
    id, actor_user_id, actor_role, actor_school_id, target_school_id,
    http_method, request_path, response_status, source_fingerprint, occurred_at
) VALUES
    (1, 10, 'REGULATOR', NULL, 1,
     'POST', '/api/supervision/alerts/scan', 200,
     'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
     '2026-07-22 08:00:00.000'),
    (2, 11, 'SCHOOL_ADMIN', 1, 1,
     'POST', '/api/supervision/alerts/1/transition', 200,
     'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
     '2026-07-23 15:00:00.000');

INSERT INTO course_evaluation (
    id, school_id, offering_id, enrollment_id, student_id, guardian_id,
    rating, comment, submitted_at
) VALUES (
    1, 1, 5, 3, 2, 1,
    4, '课程内容丰富，孩子愿意继续参加，希望增加作品展示环节。',
    '2026-07-25 20:10:00.000'
);
