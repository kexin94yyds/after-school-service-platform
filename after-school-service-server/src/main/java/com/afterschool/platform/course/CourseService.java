package com.afterschool.platform.course;

import com.afterschool.platform.academic.AcademicMapper;
import com.afterschool.platform.academic.AcademicTerm;
import com.afterschool.platform.academic.RoomResource;
import com.afterschool.platform.academic.ServicePlan;
import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseMapper mapper;
    private final AcademicMapper academicMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public CourseService(
            CourseMapper mapper,
            AcademicMapper academicMapper,
            CurrentUser currentUser,
            Clock clock) {
        this.mapper = mapper;
        this.academicMapper = academicMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public List<Map<String, Object>> courses(Long requestedSchoolId) {
        return mapper.listCourses(currentUser.optionalSchoolScope(requestedSchoolId));
    }

    @Transactional
    public Map<String, Object> createCourse(CourseController.CourseRequest request) {
        validateCourse(request);
        long schoolId = currentUser.schoolScope(request.schoolId());
        mapper.insertCourse(
                schoolId,
                request.courseCode().strip(),
                request.courseName().strip(),
                request.category().strip(),
                trimToNull(request.description()),
                request.targetGradeMin(),
                request.targetGradeMax(),
                request.defaultCapacity(),
                request.status(),
                currentUser.principal().id());
        return mapper.findCourseByCode(schoolId, request.courseCode().strip());
    }

    @Transactional
    public Map<String, Object> updateCourse(long id, CourseController.CourseRequest request) {
        validateCourse(request);
        long schoolId = currentUser.schoolScope(request.schoolId());
        if (mapper.lockCourse(id, schoolId) == null) {
            throw ApiException.notFound("课程不存在或不在当前学校");
        }
        if (mapper.countCourseDependencies(id) > 0
                && mapper.countCourseRuleDifferences(
                                id,
                                schoolId,
                                request.courseCode().strip(),
                                request.targetGradeMin(),
                                request.targetGradeMax())
                        > 0) {
            throw ApiException.conflict(
                    "COURSE_RULES_FROZEN",
                    "已有开班后不能修改课程编码或适用年级范围");
        }
        if (mapper.updateCourse(
                        id,
                        schoolId,
                        request.courseCode().strip(),
                        request.courseName().strip(),
                        request.category().strip(),
                        trimToNull(request.description()),
                        request.targetGradeMin(),
                        request.targetGradeMax(),
                        request.defaultCapacity(),
                        request.status())
                == 0) {
            throw ApiException.notFound("课程不存在或不在当前学校");
        }
        return mapper.findCourseByCode(schoolId, request.courseCode().strip());
    }

    public List<Map<String, Object>> offerings(Long requestedSchoolId) {
        PlatformPrincipal principal = currentUser.principal();
        Long teacherId = null;
        if ("TEACHER".equals(principal.roleCode())) {
            if (principal.teacherId() == null) {
                throw ApiException.forbidden("当前教师账号缺少教师档案");
            }
            teacherId = principal.teacherId();
        }
        return mapper.listOfferings(currentUser.optionalSchoolScope(requestedSchoolId), teacherId);
    }

    @Transactional
    public Map<String, Object> createOffering(CourseController.OfferingRequest request) {
        if (!"DRAFT".equals(request.status())) {
            throw ApiException.badRequest(
                    "INITIAL_OFFERING_STATUS_INVALID",
                    "新建开班状态必须为草稿");
        }
        validateOffering(request);
        long schoolId = currentUser.schoolScope(request.schoolId());
        AcademicReferences academic = lockAcademicReferences(schoolId, request);
        requireTeacher(schoolId, request.teacherId());
        RoomResource room = lockRoom(schoolId, request, academic);
        requireActiveCourse(schoolId, request.courseId());
        requireNoTeacherConflict(null, request);
        requireNoRoomConflict(null, schoolId, request);
        String classroom = room == null ? request.classroom().strip() : room.getRoomName();
        mapper.insertOffering(
                schoolId,
                request.courseId(),
                request.teacherId(),
                request.offeringCode().strip(),
                request.term().strip(),
                request.weekDay(),
                request.startTime(),
                request.endTime(),
                request.startDate(),
                request.endDate(),
                request.enrollmentStart(),
                request.enrollmentEnd(),
                request.capacity(),
                classroom,
                "DRAFT",
                request.termId(),
                request.planId(),
                request.roomId());
        return mapper.findOfferingByCode(schoolId, request.offeringCode().strip());
    }

    @Transactional
    public Map<String, Object> updateOffering(long id, CourseController.OfferingRequest request) {
        validateOffering(request);
        long schoolId = currentUser.schoolScope(request.schoolId());
        boolean lifecycleOnly = List.of("CLOSED", "FINISHED", "CANCELED")
                .contains(request.status());
        RoomResource room = null;
        if (!lifecycleOnly) {
            // Mutable scheduling locks term -> plan -> teacher -> room -> offering -> course.
            AcademicReferences academic = lockAcademicReferences(schoolId, request);
            requireTeacher(schoolId, request.teacherId());
            room = lockRoom(schoolId, request, academic);
        }
        String currentStatus = mapper.offeringStatus(id, schoolId);
        if (currentStatus == null) {
            throw ApiException.notFound("开班不存在或不在当前学校");
        }
        if (!lifecycleOnly) {
            requireActiveCourse(schoolId, request.courseId());
        }
        validateOfferingTransition(currentStatus, request.status());
        if ("FINISHED".equals(request.status())
                && !"FINISHED".equals(currentStatus)
                && mapper.countScheduledSessions(id) > 0) {
            throw ApiException.conflict(
                    "OFFERING_HAS_SCHEDULED_SESSIONS",
                    "仍有未完成课次，不能结束开班");
        }
        LocalDateTime transitionTime = LocalDateTime.now(clock);
        if ("CANCELED".equals(request.status())
                && !"CANCELED".equals(currentStatus)
                && mapper.countStartedScheduledSessions(id, transitionTime) > 0) {
            throw ApiException.conflict(
                    "OFFERING_HAS_UNRESOLVED_SESSIONS",
                    "存在已开始但未完成的课次，请先完成考勤或逐课次取消");
        }
        String classroom = room == null ? request.classroom().strip() : room.getRoomName();
        int shapeDifferences = mapper.countOfferingShapeDifferences(
                                id,
                                schoolId,
                                request.courseId(),
                                request.teacherId(),
                                request.offeringCode().strip(),
                                request.term().strip(),
                                request.weekDay(),
                                request.startTime(),
                                request.endTime(),
                                request.startDate(),
                                request.endDate(),
                                request.enrollmentStart(),
                                request.enrollmentEnd(),
                                classroom,
                                request.termId(),
                                request.planId(),
                                request.roomId());
        if (shapeDifferences > 0
                && (lifecycleOnly || mapper.countOfferingDependencies(id) > 0)) {
            throw ApiException.conflict(
                    "OFFERING_SCHEDULE_FROZEN",
                    "已有报名或课次后不能修改开班课程、教师或排课信息");
        }
        if (!lifecycleOnly) {
            requireNoTeacherConflict(id, request);
            requireNoRoomConflict(id, schoolId, request);
        }
        if (mapper.updateOffering(
                        id,
                        schoolId,
                        request.courseId(),
                        request.teacherId(),
                        request.offeringCode().strip(),
                        request.term().strip(),
                        request.weekDay(),
                        request.startTime(),
                        request.endTime(),
                        request.startDate(),
                        request.endDate(),
                        request.enrollmentStart(),
                        request.enrollmentEnd(),
                        request.capacity(),
                        classroom,
                        request.status(),
                        request.termId(),
                        request.planId(),
                        request.roomId())
                == 0) {
            throw ApiException.notFound("开班不存在、不在当前学校或容量低于已报名人数");
        }
        if ("CANCELED".equals(request.status())
                && !"CANCELED".equals(currentStatus)) {
            mapper.cancelActiveEnrollments(id, currentUser.principal().id());
            mapper.cancelScheduledSessions(id, transitionTime);
        }
        return mapper.findOfferingByCode(schoolId, request.offeringCode().strip());
    }

    private void requireTeacher(long schoolId, long teacherId) {
        if (mapper.lockTeacher(teacherId, schoolId) == null) {
            throw ApiException.badRequest("INVALID_TEACHER", "教师不存在或不属于当前学校");
        }
    }

    private void requireActiveCourse(long schoolId, long courseId) {
        if (mapper.lockActiveCourse(courseId, schoolId) == null) {
            throw ApiException.badRequest("INVALID_COURSE", "课程不存在或不属于当前学校");
        }
    }

    private void requireNoTeacherConflict(Long excludeId, CourseController.OfferingRequest request) {
        if (mapper.findTeacherConflict(
                        request.teacherId(),
                        excludeId,
                        request.weekDay(),
                        request.startTime(),
                        request.endTime(),
                        request.startDate(),
                        request.endDate())
                != null) {
            throw ApiException.conflict("TEACHER_SCHEDULE_CONFLICT", "该教师在所选时段已有其他开班");
        }
    }

    private AcademicReferences lockAcademicReferences(
            long schoolId, CourseController.OfferingRequest request) {
        AcademicTerm term = null;
        if (request.termId() != null) {
            term = academicMapper.lockTerm(request.termId());
            if (term == null) {
                throw ApiException.badRequest("INVALID_TERM", "学期不存在");
            }
            if (List.of("CLOSED", "ARCHIVED").contains(term.getStatus())) {
                throw ApiException.conflict(
                        "TERM_NOT_AVAILABLE",
                        "已结束或归档学期不能新建或调整开班");
            }
            if (request.startDate().isBefore(term.getStartDate())
                    || request.endDate().isAfter(term.getEndDate())) {
                throw ApiException.badRequest(
                        "OFFERING_OUTSIDE_TERM",
                        "开班日期范围必须位于关联学期内");
            }
        }
        ServicePlan plan = null;
        if (request.planId() != null) {
            if (term == null) {
                throw ApiException.badRequest(
                        "TERM_REQUIRED_FOR_PLAN",
                        "关联服务计划时必须同时关联学期");
            }
            plan = academicMapper.lockServicePlan(request.planId(), schoolId);
            if (plan == null) {
                throw ApiException.badRequest(
                        "INVALID_SERVICE_PLAN",
                        "服务计划不存在或不属于当前学校");
            }
            if (plan.getTermId() != term.getId()) {
                throw ApiException.badRequest(
                        "PLAN_TERM_MISMATCH",
                        "服务计划与开班学期不一致");
            }
            if ("PUBLISHED".equals(request.status())
                    && !List.of("FILED", "ACTIVE").contains(plan.getStatus())) {
                throw ApiException.conflict(
                        "PLAN_NOT_FILED",
                        "开班发布前，关联服务计划必须已备案或生效");
            }
        }
        return new AcademicReferences(term, plan);
    }

    private RoomResource lockRoom(
            long schoolId,
            CourseController.OfferingRequest request,
            AcademicReferences academic) {
        if (request.roomId() == null) {
            return null;
        }
        RoomResource room = academicMapper.lockRoom(request.roomId(), schoolId);
        if (room == null || !"ACTIVE".equals(room.getStatus())) {
            throw ApiException.badRequest(
                    "INVALID_ROOM",
                    "教室不存在、不属于当前学校或已停用");
        }
        if (room.getCapacity() < request.capacity()) {
            throw ApiException.badRequest(
                    "ROOM_CAPACITY_TOO_SMALL",
                    "教室容量不能低于开班容量");
        }
        return room;
    }

    private void requireNoRoomConflict(
            Long excludeId,
            long schoolId,
            CourseController.OfferingRequest request) {
        if (request.roomId() != null
                && academicMapper.findRoomOfferingConflict(
                                schoolId,
                                request.roomId(),
                                excludeId,
                                request.weekDay(),
                                request.startTime(),
                                request.endTime(),
                                request.startDate(),
                                request.endDate())
                        != null) {
            throw ApiException.conflict(
                    "ROOM_SCHEDULE_CONFLICT",
                    "该教室在所选时段已有其他开班");
        }
    }

    private void validateCourse(CourseController.CourseRequest request) {
        if (request.targetGradeMin() > request.targetGradeMax()) {
            throw ApiException.badRequest("INVALID_GRADE_RANGE", "最低适用年级不能高于最高适用年级");
        }
    }

    private void validateOffering(CourseController.OfferingRequest request) {
        if (!request.startTime().isBefore(request.endTime())) {
            throw ApiException.badRequest("INVALID_TIME_RANGE", "上课开始时间必须早于结束时间");
        }
        if (request.startDate().isAfter(request.endDate())) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "开班开始日期不能晚于结束日期");
        }
        if (!request.enrollmentStart().isBefore(request.enrollmentEnd())) {
            throw ApiException.badRequest("INVALID_ENROLLMENT_WINDOW", "报名开始时间必须早于结束时间");
        }
    }

    private void validateOfferingTransition(String current, String target) {
        boolean allowed = switch (current) {
            case "DRAFT" -> List.of("DRAFT", "PUBLISHED", "CANCELED").contains(target);
            case "PUBLISHED" -> List.of("PUBLISHED", "CLOSED", "CANCELED").contains(target);
            case "CLOSED" -> List.of("CLOSED", "FINISHED", "CANCELED").contains(target);
            case "FINISHED" -> "FINISHED".equals(target);
            case "CANCELED" -> "CANCELED".equals(target);
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict(
                    "INVALID_OFFERING_TRANSITION",
                    "开班状态不能从 " + current + " 变更为 " + target);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record AcademicReferences(AcademicTerm term, ServicePlan plan) {}
}
