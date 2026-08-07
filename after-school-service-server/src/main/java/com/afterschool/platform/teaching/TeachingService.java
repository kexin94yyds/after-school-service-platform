package com.afterschool.platform.teaching;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingService {

    private final TeachingMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TeachingService(TeachingMapper mapper, CurrentUser currentUser, Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public List<Map<String, Object>> generateSessions(long offeringId) {
        OfferingSchedule offering = requireOfferingAccess(offeringId, true);
        if (!List.of("PUBLISHED", "CLOSED").contains(offering.getStatus())) {
            throw ApiException.conflict("OFFERING_NOT_SCHEDULED", "只有已发布或已关闭报名的开班可生成课次");
        }

        Set<LocalDate> closedCalendarDates = offering.getTermId() == null
                ? Set.of()
                : new HashSet<>(mapper.listClosedCalendarDates(
                        offering.getSchoolId(),
                        offering.getTermId(),
                        offering.getStartDate(),
                        offering.getEndDate()));
        Set<LocalDate> rescheduledOriginalDates =
                new HashSet<>(mapper.listAppliedRescheduleOriginalDates(offeringId));
        LocalDate date = offering.getStartDate();
        DayOfWeek target = DayOfWeek.of(offering.getWeekDay());
        while (date.getDayOfWeek() != target) {
            date = date.plusDays(1);
        }
        while (!date.isAfter(offering.getEndDate())) {
            if (!closedCalendarDates.contains(date)
                    && !rescheduledOriginalDates.contains(date)) {
                mapper.insertSession(
                        offering.getSchoolId(),
                        offeringId,
                        date,
                        offering.getStartTime(),
                        offering.getEndTime(),
                        offering.getClassroom());
            }
            date = date.plusWeeks(1);
        }
        return mapper.listSessions(offeringId);
    }

    public List<Map<String, Object>> sessions(long offeringId) {
        requireOfferingAccess(offeringId, false);
        return mapper.listSessions(offeringId);
    }

    @Transactional
    public Map<String, Object> updateSession(
            long sessionId, TeachingController.SessionRequest request) {
        LessonOwner lesson = requireLessonAccess(sessionId, true);
        validateSessionTransition(lesson.getStatus(), request.status());
        if ("CANCELED".equals(request.status())
                && mapper.countSessionAttendance(sessionId) > 0) {
            throw ApiException.conflict(
                    "SESSION_HAS_ATTENDANCE",
                    "已有考勤记录的课次不能取消");
        }
        if (mapper.updateSession(sessionId, request.status(), trimToNull(request.notes())) != 1) {
            throw ApiException.notFound("课次不存在");
        }
        return mapper.listSessions(lesson.getOfferingId()).stream()
                .filter(item -> ((Number) item.get("id")).longValue() == sessionId)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课次不存在"));
    }

    public List<Map<String, Object>> attendance(long sessionId) {
        requireLessonAccess(sessionId, false);
        return mapper.listAttendance(sessionId);
    }

    @Transactional
    public List<Map<String, Object>> saveAttendance(
            long sessionId, TeachingController.AttendanceBatch request) {
        LessonOwner lesson = requireLessonAccess(sessionId, true);
        if ("CANCELED".equals(lesson.getOfferingStatus())) {
            throw ApiException.conflict("OFFERING_CANCELED", "已取消开班不能登记考勤");
        }
        if ("CANCELED".equals(lesson.getStatus())) {
            throw ApiException.conflict("SESSION_CANCELED", "已取消课次不能登记考勤");
        }
        if ("COMPLETED".equals(lesson.getStatus())) {
            throw ApiException.conflict(
                    "SESSION_COMPLETED",
                    "已完成课次的考勤已锁定，不能直接覆盖");
        }
        LocalDateTime sessionStart =
                LocalDateTime.of(lesson.getSessionDate(), lesson.getStartTime());
        if (LocalDateTime.now(clock).isBefore(sessionStart)) {
            throw ApiException.conflict(
                    "ATTENDANCE_NOT_STARTED",
                    "课次开始前不能登记考勤");
        }
        if (request.records().stream().map(TeachingController.AttendanceRecord::studentId).distinct().count()
                != request.records().size()) {
            throw ApiException.badRequest("DUPLICATE_ATTENDANCE", "同一学生不能在一次提交中重复出现");
        }
        Set<Long> roster = new HashSet<>();
        for (Map<String, Object> item : mapper.listAttendance(sessionId)) {
            roster.add(((Number) item.get("studentId")).longValue());
        }
        Set<Long> submitted = new HashSet<>();
        for (TeachingController.AttendanceRecord record : request.records()) {
            submitted.add(record.studentId());
        }
        if (!submitted.equals(roster)) {
            throw ApiException.badRequest(
                    "INCOMPLETE_ATTENDANCE",
                    "考勤提交必须完整覆盖该课次名单，且不能包含名单外学生");
        }
        for (TeachingController.AttendanceRecord record : request.records()) {
            if (mapper.countAttendanceEligible(
                            lesson.getOfferingId(), sessionId, record.studentId())
                    != 1) {
                throw ApiException.badRequest(
                        "STUDENT_NOT_ENROLLED", "考勤学生不在该开班的有效报名名单中");
            }
            mapper.upsertAttendance(
                    lesson.getSchoolId(),
                    lesson.getOfferingId(),
                    sessionId,
                    record.studentId(),
                    record.status(),
                    currentUser.principal().id(),
                    trimToNull(record.remark()));
        }
        mapper.updateSession(sessionId, "COMPLETED", null);
        return mapper.listAttendance(sessionId);
    }

    private OfferingSchedule requireOfferingAccess(long offeringId, boolean write) {
        OfferingSchedule offering = write
                ? mapper.lockOfferingSchedule(offeringId)
                : mapper.findOfferingSchedule(offeringId);
        if (offering == null) {
            throw ApiException.notFound("开班不存在");
        }
        PlatformPrincipal principal = currentUser.principal();
        switch (principal.roleCode()) {
            case "REGULATOR" -> {
                if (write) {
                    throw ApiException.forbidden("监管账号只有查询权限");
                }
            }
            case "SCHOOL_ADMIN" -> {
                if (principal.schoolId() == null || principal.schoolId() != offering.getSchoolId()) {
                    throw ApiException.forbidden("不能访问其他学校的开班");
                }
            }
            case "TEACHER" -> {
                if (principal.teacherId() == null || principal.teacherId() != offering.getTeacherId()) {
                    throw ApiException.forbidden("只能访问本人负责的开班");
                }
            }
            default -> throw ApiException.forbidden("当前角色不能访问课次");
        }
        return offering;
    }

    private LessonOwner requireLessonAccess(long sessionId, boolean write) {
        LessonOwner lesson;
        if (write) {
            Long offeringId = mapper.findLessonOfferingId(sessionId);
            if (offeringId == null) {
                throw ApiException.notFound("课次不存在");
            }
            LessonOwner offeringAccess = mapper.lockOfferingAccess(offeringId);
            if (offeringAccess == null) {
                throw ApiException.notFound("课次所属开班不存在");
            }
            lesson = mapper.lockLessonOwner(sessionId);
            if (lesson == null || lesson.getOfferingId() != offeringId) {
                throw ApiException.notFound("课次不存在或所属开班已变化");
            }
            lesson.setTeacherId(offeringAccess.getTeacherId());
            lesson.setOfferingStatus(offeringAccess.getOfferingStatus());
        } else {
            lesson = mapper.findLessonOwner(sessionId);
        }
        if (lesson == null) {
            throw ApiException.notFound("课次不存在");
        }
        PlatformPrincipal principal = currentUser.principal();
        switch (principal.roleCode()) {
            case "REGULATOR" -> {
                if (write) {
                    throw ApiException.forbidden("监管账号只有查询权限");
                }
            }
            case "SCHOOL_ADMIN" -> {
                if (principal.schoolId() == null || principal.schoolId() != lesson.getSchoolId()) {
                    throw ApiException.forbidden("不能访问其他学校的课次");
                }
            }
            case "TEACHER" -> {
                if (principal.teacherId() == null || principal.teacherId() != lesson.getTeacherId()) {
                    throw ApiException.forbidden("只能操作本人负责的课次");
                }
            }
            default -> throw ApiException.forbidden("当前角色不能访问考勤");
        }
        return lesson;
    }

    private void validateSessionTransition(String current, String target) {
        boolean allowed = switch (current) {
            case "SCHEDULED" -> "SCHEDULED".equals(target) || "CANCELED".equals(target);
            case "COMPLETED" -> "COMPLETED".equals(target);
            case "CANCELED" -> "CANCELED".equals(target);
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict(
                    "INVALID_SESSION_TRANSITION",
                    "课次状态不能从 " + current + " 变更为 " + target);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
