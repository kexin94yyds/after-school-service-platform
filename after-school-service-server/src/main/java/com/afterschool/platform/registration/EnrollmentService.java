package com.afterschool.platform.registration;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import com.afterschool.platform.common.excel.SimpleXlsx;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    private final EnrollmentMapper mapper;
    private final CurrentUser currentUser;
    private final EnrollmentRuleEngine rules;
    private final Clock clock;

    public EnrollmentService(
            EnrollmentMapper mapper,
            CurrentUser currentUser,
            EnrollmentRuleEngine rules,
            Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.rules = rules;
        this.clock = clock;
    }

    public List<Map<String, Object>> guardianStudents() {
        return mapper.guardianStudents(requireGuardianId());
    }

    public List<Map<String, Object>> guardianOfferings(long studentId) {
        long guardianId = requireGuardianId();
        EnrollmentStudent student = mapper.findGuardianStudent(studentId, guardianId);
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (Map<String, Object> source : mapper.listGuardianOfferings(student.getSchoolId())) {
            long offeringId = ((Number) source.get("id")).longValue();
            EnrollmentOffering offering = mapper.findOffering(offeringId);
            EnrollmentState state = mapper.findEnrollmentState(offeringId, studentId);
            boolean conflict = mapper.countScheduleConflicts(studentId, offeringId) > 0;
            Map<String, Object> item = new LinkedHashMap<>(source);
            try {
                rules.validate(
                        student,
                        offering,
                        state,
                        conflict,
                        effectiveFirstSessionStart(offering),
                        LocalDateTime.now(clock));
                item.put("canEnroll", true);
                item.put("eligibilityCode", "ELIGIBLE");
                item.put("eligibilityMessage", "可报名");
            } catch (ApiException exception) {
                item.put("canEnroll", false);
                item.put("eligibilityCode", exception.code());
                item.put("eligibilityMessage", exception.getMessage());
            }
            item.put("enrollmentStatus", state == null ? null : state.getStatus());
            response.add(item);
        }
        return response;
    }

    public List<Map<String, Object>> guardianAttendance(long studentId) {
        EnrollmentStudent student = mapper.findGuardianStudent(studentId, requireGuardianId());
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        return mapper.listGuardianAttendance(studentId);
    }

    public Map<String, Object> guardianMonthlyAttendance(
            long studentId, String requestedMonth) {
        EnrollmentStudent student = mapper.findGuardianStudent(
                studentId, requireGuardianId());
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(requestedMonth);
        } catch (DateTimeException exception) {
            throw ApiException.badRequest(
                    "INVALID_MONTH", "月份必须使用 YYYY-MM 格式");
        }
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("studentId", studentId);
        response.put("month", month.toString());
        Map<String, Object> summary = mapper.guardianAttendanceSummary(
                studentId, startDate, endDate);
        response.put("summary", summary == null ? Map.of() : summary);
        response.put("records", mapper.listGuardianAttendanceByRange(
                studentId, startDate, endDate));
        return response;
    }

    @Transactional
    public Map<String, Object> enroll(EnrollmentController.EnrollmentRequest request) {
        long guardianId = requireGuardianId();

        // Fixed lock order: offering first, then student. Rescheduling follows the
        // same order before it locks every enrolled student, so enrollment and
        // schedule mutations cannot validate different versions of one child’s
        // timetable concurrently.
        EnrollmentOffering offering = mapper.lockOffering(request.offeringId());
        if (offering == null) {
            throw ApiException.notFound("开班不存在");
        }
        // Keep the exclusive offering/course lock narrow. Parent statuses are
        // read only after that lock has been acquired, so closing a term or
        // plan can lock parent -> offerings without a reverse lock edge.
        EnrollmentOffering currentOffering = mapper.findOffering(request.offeringId());
        if (currentOffering == null) {
            throw ApiException.notFound("开班不存在");
        }
        EnrollmentStudent student = mapper.lockGuardianStudent(request.studentId(), guardianId);
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }

        EnrollmentState existing =
                mapper.findEnrollmentState(request.offeringId(), request.studentId());
        boolean conflict =
                mapper.countScheduleConflicts(request.studentId(), request.offeringId()) > 0;
        rules.validate(
                student,
                currentOffering,
                existing,
                conflict,
                effectiveFirstSessionStart(currentOffering),
                LocalDateTime.now(clock));

        if (mapper.incrementCapacity(request.offeringId()) != 1) {
            throw ApiException.conflict("OFFERING_FULL", "该开班名额已满");
        }
        if (existing == null) {
            mapper.insertEnrollment(
                    student.getSchoolId(),
                    request.offeringId(),
                    request.studentId(),
                    guardianId);
        } else {
            mapper.reactivateEnrollment(existing.getId(), guardianId);
        }
        Map<String, Object> created = mapper.findEnrollmentView(
                request.offeringId(), request.studentId());
        recordEnrollmentAction(
                created,
                existing == null ? "ENROLL" : "REACTIVATE",
                null,
                currentUser.principal());
        return created;
    }

    @Transactional
    public void cancel(long enrollmentId) {
        PlatformPrincipal principal = currentUser.principal();
        boolean guardianCancellation = "GUARDIAN".equals(principal.roleCode());
        EnrollmentRecord record;
        Long guardianId = null;
        if (guardianCancellation) {
            guardianId = requireGuardianId();
            record = mapper.findScopedEnrollment(enrollmentId, guardianId);
        } else if ("SCHOOL_ADMIN".equals(principal.roleCode())
                && principal.schoolId() != null) {
            record = mapper.findSchoolEnrollment(enrollmentId, principal.schoolId());
        } else {
            throw ApiException.forbidden("当前角色不能取消报名");
        }
        if (record == null) {
            throw ApiException.notFound("报名记录不存在");
        }

        // Keep cancellation on the same offering -> student lock order as
        // enrollment and rescheduling.
        EnrollmentOffering offering = mapper.lockOffering(record.getOfferingId());
        if (offering == null) {
            throw ApiException.notFound("开班不存在");
        }
        if (guardianCancellation) {
            EnrollmentStudent student =
                    mapper.lockGuardianStudent(record.getStudentId(), guardianId);
            if (student == null) {
                throw ApiException.notFound("报名学生未与当前家长绑定");
            }
        } else if (mapper.lockSchoolStudent(
                        record.getStudentId(), principal.schoolId())
                == null) {
            throw ApiException.notFound("报名学生不存在或不在当前学校");
        }
        EnrollmentRecord lockedRecord = guardianCancellation
                ? mapper.lockScopedEnrollment(enrollmentId, guardianId)
                : mapper.lockSchoolEnrollment(enrollmentId, principal.schoolId());
        if (lockedRecord == null
                || lockedRecord.getStudentId() != record.getStudentId()
                || lockedRecord.getOfferingId() != record.getOfferingId()) {
            throw ApiException.conflict(
                    "ENROLLMENT_CHANGED",
                    "报名归属或关联对象已变化，请刷新后重试");
        }
        if (guardianCancellation) {
            rules.validateCancellation(
                    offering,
                    effectiveFirstSessionStart(offering),
                    LocalDateTime.now(clock));
        }
        if (!"ENROLLED".equals(lockedRecord.getStatus())) {
            throw ApiException.conflict("ALREADY_CANCELED", "该报名已经取消");
        }
        if (mapper.cancelEnrollment(
                                enrollmentId,
                                guardianCancellation ? guardianId : null,
                                guardianCancellation ? null : principal.schoolId(),
                                principal.id())
                        != 1
                || mapper.decrementCapacity(record.getOfferingId()) != 1) {
            throw ApiException.conflict("ENROLLMENT_CHANGED", "报名状态已变化，请刷新后重试");
        }
        mapper.withdrawActiveLeavesForEnrollment(
                offering.getSchoolId(),
                lockedRecord.getOfferingId(),
                lockedRecord.getStudentId(),
                principal.id());
        Map<String, Object> canceled = mapper.findEnrollmentView(
                lockedRecord.getOfferingId(), lockedRecord.getStudentId());
        recordEnrollmentAction(canceled, "CANCEL", null, principal);
    }

    @Transactional
    public Map<String, Object> switchEnrollment(
            long enrollmentId,
            long newOfferingId) {
        PlatformPrincipal principal = currentUser.principal();
        long guardianId = requireGuardianId();
        EnrollmentRecord snapshot = mapper.findScopedEnrollment(
                enrollmentId, guardianId);
        if (snapshot == null) {
            throw ApiException.notFound("原报名记录不存在");
        }
        if (snapshot.getOfferingId() == newOfferingId) {
            throw ApiException.badRequest(
                    "SAME_OFFERING", "改选目标不能与原课程相同");
        }

        long firstOfferingId = Math.min(snapshot.getOfferingId(), newOfferingId);
        long secondOfferingId = Math.max(snapshot.getOfferingId(), newOfferingId);
        if (mapper.lockOffering(firstOfferingId) == null
                || mapper.lockOffering(secondOfferingId) == null) {
            throw ApiException.notFound("原开班或目标开班不存在");
        }
        EnrollmentOffering oldOffering = mapper.findOffering(snapshot.getOfferingId());
        EnrollmentOffering newOffering = mapper.findOffering(newOfferingId);
        if (oldOffering == null || newOffering == null) {
            throw ApiException.notFound("原开班或目标开班不存在");
        }
        EnrollmentStudent student = mapper.lockGuardianStudent(
                snapshot.getStudentId(), guardianId);
        if (student == null) {
            throw ApiException.notFound("报名学生未与当前家长绑定");
        }
        EnrollmentRecord oldEnrollment = mapper.lockScopedEnrollment(
                enrollmentId, guardianId);
        if (oldEnrollment == null || !"ENROLLED".equals(oldEnrollment.getStatus())) {
            throw ApiException.conflict(
                    "ENROLLMENT_CHANGED", "原报名状态已变化，请刷新后重试");
        }
        rules.validateCancellation(
                oldOffering,
                effectiveFirstSessionStart(oldOffering),
                LocalDateTime.now(clock));
        if (mapper.cancelEnrollment(enrollmentId, guardianId, null, principal.id()) != 1
                || mapper.decrementCapacity(oldOffering.getId()) != 1) {
            throw ApiException.conflict(
                    "ENROLLMENT_CHANGED", "原报名状态已变化，请刷新后重试");
        }
        mapper.withdrawActiveLeavesForEnrollment(
                oldOffering.getSchoolId(),
                oldOffering.getId(),
                student.getId(),
                principal.id());

        EnrollmentState targetState = mapper.findEnrollmentState(
                newOfferingId, student.getId());
        boolean conflict = mapper.countScheduleConflicts(
                student.getId(), newOfferingId) > 0;
        rules.validate(
                student,
                newOffering,
                targetState,
                conflict,
                effectiveFirstSessionStart(newOffering),
                LocalDateTime.now(clock));
        if (mapper.incrementCapacity(newOfferingId) != 1) {
            throw ApiException.conflict("OFFERING_FULL", "目标开班名额已满");
        }
        if (targetState == null) {
            mapper.insertEnrollment(
                    student.getSchoolId(), newOfferingId, student.getId(), guardianId);
        } else {
            mapper.reactivateEnrollment(targetState.getId(), guardianId);
        }

        Map<String, Object> oldView = mapper.findEnrollmentView(
                oldOffering.getId(), student.getId());
        Map<String, Object> newView = mapper.findEnrollmentView(
                newOfferingId, student.getId());
        long newEnrollmentId = ((Number) newView.get("id")).longValue();
        recordEnrollmentAction(oldView, "SWITCH_OUT", newEnrollmentId, principal);
        recordEnrollmentAction(newView, "SWITCH_IN", enrollmentId, principal);
        return newView;
    }

    public List<Map<String, Object>> enrollmentActions(long enrollmentId) {
        PlatformPrincipal principal = currentUser.principal();
        EnrollmentRecord record;
        Long schoolId = null;
        Long guardianId = null;
        switch (principal.roleCode()) {
            case "REGULATOR" -> record = mapper.findEnrollment(enrollmentId);
            case "SCHOOL_ADMIN" -> {
                if (principal.schoolId() == null) {
                    throw ApiException.forbidden("当前账号没有学校数据权限");
                }
                schoolId = principal.schoolId();
                record = mapper.findSchoolEnrollment(enrollmentId, schoolId);
            }
            case "GUARDIAN" -> {
                guardianId = requireGuardianId();
                record = mapper.findScopedEnrollment(enrollmentId, guardianId);
            }
            default -> throw ApiException.forbidden("当前角色不能查看报名变更历史");
        }
        if (record == null) {
            throw ApiException.notFound("报名记录不存在或不在当前数据范围内");
        }
        return mapper.listEnrollmentActions(enrollmentId, schoolId, guardianId);
    }

    public List<Map<String, Object>> enrollments(Long requestedSchoolId) {
        PlatformPrincipal principal = currentUser.principal();
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        Long teacherId = null;
        Long guardianId = null;
        if ("TEACHER".equals(principal.roleCode())) {
            if (principal.teacherId() == null) {
                throw ApiException.forbidden("当前教师账号缺少教师档案");
            }
            teacherId = principal.teacherId();
        }
        if ("GUARDIAN".equals(principal.roleCode())) {
            if (principal.guardianId() == null) {
                throw ApiException.forbidden("当前家长账号缺少家长档案");
            }
            guardianId = principal.guardianId();
        }
        return mapper.listEnrollments(schoolId, teacherId, guardianId);
    }

    public byte[] rosterXlsx(long offeringId) {
        Long schoolId = currentUser.optionalSchoolScope(null);
        List<Map<String, Object>> rows = mapper.listEnrollmentRoster(
                offeringId, schoolId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("开班不存在或当前没有报名记录");
        }
        List<String> headers = List.of(
                "开班编号",
                "课程名称",
                "班级",
                "学号",
                "学生姓名",
                "家长姓名",
                "联系电话",
                "报名状态",
                "报名时间");
        List<? extends List<?>> data = rows.stream()
                .map(row -> List.of(
                        excelValue(row, "offeringCode"),
                        excelValue(row, "courseName"),
                        excelValue(row, "className"),
                        excelValue(row, "studentNo"),
                        excelValue(row, "studentName"),
                        excelValue(row, "guardianName"),
                        excelValue(row, "guardianMobile"),
                        excelValue(row, "status"),
                        excelValue(row, "enrolledAt")))
                .toList();
        return SimpleXlsx.write("选课名单", headers, data);
    }

    private long requireGuardianId() {
        PlatformPrincipal principal = currentUser.principal();
        if (!"GUARDIAN".equals(principal.roleCode()) || principal.guardianId() == null) {
            throw ApiException.forbidden("当前账号不是有效家长账号");
        }
        return principal.guardianId();
    }

    private LocalDateTime effectiveFirstSessionStart(EnrollmentOffering offering) {
        LocalDateTime actualStart = mapper.findFirstValidSessionStart(offering.getId());
        return actualStart == null
                ? rules.templateFirstSessionStart(offering)
                : actualStart;
    }

    private void recordEnrollmentAction(
            Map<String, Object> enrollment,
            String actionType,
            Long relatedEnrollmentId,
            PlatformPrincipal principal) {
        if (enrollment == null
                || mapper.insertEnrollmentAction(
                                ((Number) enrollment.get("schoolId")).longValue(),
                                ((Number) enrollment.get("id")).longValue(),
                                ((Number) enrollment.get("offeringId")).longValue(),
                                ((Number) enrollment.get("studentId")).longValue(),
                                ((Number) enrollment.get("guardianId")).longValue(),
                                actionType,
                                relatedEnrollmentId,
                                principal.id(),
                                principal.roleCode())
                        != 1) {
            throw ApiException.conflict(
                    "ENROLLMENT_ACTION_NOT_RECORDED",
                    "报名变更历史未能保存，操作已回滚");
        }
    }

    private Object excelValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value;
    }
}
