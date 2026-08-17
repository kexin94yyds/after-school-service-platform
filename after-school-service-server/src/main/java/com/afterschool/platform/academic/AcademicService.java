package com.afterschool.platform.academic;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicService {

    private final AcademicMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AcademicService(AcademicMapper mapper, CurrentUser currentUser, Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public List<Map<String, Object>> terms() {
        return mapper.listTerms();
    }

    @Transactional
    public Map<String, Object> createTerm(AcademicController.TermRequest request) {
        if (!"DRAFT".equals(request.status())) {
            throw ApiException.badRequest(
                    "INITIAL_TERM_STATUS_INVALID",
                    "新建学期状态必须为草稿");
        }
        validateTermRange(request.startDate(), request.endDate());
        mapper.insertTerm(
                request.termCode().strip(),
                request.termName().strip(),
                request.startDate(),
                request.endDate(),
                "DRAFT",
                currentUser.principal().id());
        return mapper.findTermByCode(request.termCode().strip());
    }

    @Transactional
    public Map<String, Object> updateTerm(long id, AcademicController.TermRequest request) {
        validateTermRange(request.startDate(), request.endDate());
        AcademicTerm current = mapper.lockTerm(id);
        if (current == null) {
            throw ApiException.notFound("学期不存在");
        }
        validateTermTransition(current.getStatus(), request.status());
        boolean shapeChanged = !Objects.equals(current.getTermCode(), request.termCode().strip())
                || !Objects.equals(current.getStartDate(), request.startDate())
                || !Objects.equals(current.getEndDate(), request.endDate());
        if (shapeChanged
                && (!"DRAFT".equals(current.getStatus())
                        || mapper.countTermPlans(id) > 0)) {
            throw ApiException.conflict(
                    "TERM_SHAPE_FROZEN",
                    "学期启用或已有学校计划后不能修改编码及日期范围");
        }
        if (isClosedOrArchived(request.status())
                && !Objects.equals(current.getStatus(), request.status())) {
            mapper.lockTermOfferings(id);
        }
        if (mapper.updateTerm(
                        id,
                        request.termCode().strip(),
                        request.termName().strip(),
                        request.startDate(),
                        request.endDate(),
                        request.status())
                != 1) {
            throw ApiException.notFound("学期不存在");
        }
        return mapper.findTermByCode(request.termCode().strip());
    }

    public List<Map<String, Object>> servicePlans(Long requestedSchoolId, Long termId) {
        return mapper.listServicePlans(
                currentUser.optionalSchoolScope(requestedSchoolId), termId);
    }

    @Transactional
    public Map<String, Object> createServicePlan(
            AcademicController.ServicePlanRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        AcademicTerm term = requireWritableTerm(request.termId());
        mapper.insertServicePlan(
                schoolId,
                term.getId(),
                request.planCode().strip(),
                request.planName().strip(),
                trimToNull(request.description()),
                currentUser.principal().id());
        return mapper.findServicePlanByCode(schoolId, request.planCode().strip());
    }

    @Transactional
    public Map<String, Object> updateServicePlan(
            long id, AcademicController.ServicePlanRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        ServicePlan snapshot = mapper.findServicePlan(id);
        if (snapshot == null || snapshot.getSchoolId() != schoolId) {
            throw ApiException.notFound("服务计划不存在或不在当前学校");
        }
        requireWritableTerm(request.termId());
        ServicePlan current = mapper.lockServicePlan(id, schoolId);
        if (current == null) {
            throw ApiException.notFound("服务计划不存在或不在当前学校");
        }
        if (!List.of("DRAFT", "RETURNED").contains(current.getStatus())) {
            throw ApiException.conflict(
                    "PLAN_CONTENT_FROZEN",
                    "只有草稿或退回的服务计划可以修改");
        }
        boolean referenceChanged = current.getTermId() != request.termId()
                || !Objects.equals(current.getPlanCode(), request.planCode().strip());
        if (referenceChanged && mapper.countPlanOfferingDependencies(id) > 0) {
            throw ApiException.conflict(
                    "PLAN_REFERENCE_FROZEN",
                    "计划已关联开班，不能修改学期或计划编码");
        }
        mapper.updateServicePlan(
                id,
                schoolId,
                request.termId(),
                request.planCode().strip(),
                request.planName().strip(),
                trimToNull(request.description()));
        return mapper.findServicePlanByCode(schoolId, request.planCode().strip());
    }

    @Transactional
    public Map<String, Object> transitionServicePlan(
            long id, AcademicController.PlanTransitionRequest request) {
        ServicePlan snapshot = mapper.findServicePlan(id);
        if (snapshot == null) {
            throw ApiException.notFound("服务计划不存在");
        }
        long schoolId = currentUser.schoolScope(snapshot.getSchoolId());
        AcademicTerm term = mapper.lockTerm(snapshot.getTermId());
        if (term == null) {
            throw ApiException.notFound("服务计划所属学期不存在");
        }
        ServicePlan current = mapper.lockServicePlan(id, schoolId);
        if (current == null || current.getTermId() != term.getId()) {
            throw ApiException.notFound("服务计划不存在或所属学期已变化");
        }

        PlatformPrincipal principal = currentUser.principal();
        validatePlanTransition(
                principal.roleCode(),
                current.getStatus(),
                request.targetStatus());
        String reason = trimToNull(request.reason());
        if ("RETURNED".equals(request.targetStatus()) && reason == null) {
            throw ApiException.badRequest(
                    "RETURN_REASON_REQUIRED",
                    "退回服务计划时必须填写原因");
        }
        if ("SUBMITTED".equals(request.targetStatus())
                && List.of("CLOSED", "ARCHIVED").contains(term.getStatus())) {
            throw ApiException.conflict(
                    "TERM_NOT_WRITABLE",
                    "已结束或归档学期不能提交服务计划");
        }
        if ("ACTIVE".equals(request.targetStatus())) {
            if (!"ACTIVE".equals(term.getStatus())) {
                throw ApiException.conflict(
                        "TERM_NOT_ACTIVE",
                        "服务计划生效前，所属学期必须已启用");
            }
            if (mapper.countOtherActivePlans(schoolId, term.getId(), id) > 0) {
                throw ApiException.conflict(
                        "ACTIVE_PLAN_EXISTS",
                        "同一学校同一学期只能有一个生效中的服务计划");
            }
        }
        if (isClosedOrArchived(request.targetStatus())
                && !Objects.equals(current.getStatus(), request.targetStatus())) {
            mapper.lockPlanOfferings(id);
        }
        if (mapper.transitionServicePlan(
                        id,
                        schoolId,
                        current.getStatus(),
                        request.targetStatus(),
                        reason,
                        principal.id(),
                        LocalDateTime.now(clock))
                != 1) {
            throw ApiException.conflict(
                    "PLAN_CHANGED",
                    "服务计划已被其他操作更新，请刷新后重试");
        }
        return mapper.findServicePlanByCode(schoolId, current.getPlanCode());
    }

    public List<Map<String, Object>> rooms(Long requestedSchoolId) {
        return mapper.listRooms(currentUser.optionalSchoolScope(requestedSchoolId));
    }

    @Transactional
    public Map<String, Object> createRoom(AcademicController.RoomRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        mapper.insertRoom(
                schoolId,
                request.roomCode().strip(),
                request.roomName().strip(),
                trimToNull(request.location()),
                request.capacity(),
                request.status(),
                currentUser.principal().id());
        return mapper.findRoomByCode(schoolId, request.roomCode().strip());
    }

    @Transactional
    public Map<String, Object> updateRoom(
            long id, AcademicController.RoomRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        RoomResource current = mapper.lockRoom(id, schoolId);
        if (current == null) {
            throw ApiException.notFound("教室不存在或不在当前学校");
        }
        if ("INACTIVE".equals(request.status())
                && !"INACTIVE".equals(current.getStatus())
                && mapper.countOpenRoomOfferings(id) > 0) {
            throw ApiException.conflict(
                    "ROOM_IN_USE",
                    "教室仍有关联的未结束开班，不能停用");
        }
        if (request.capacity() < current.getCapacity()
                && mapper.countRoomCapacityViolations(id, request.capacity()) > 0) {
            throw ApiException.conflict(
                    "ROOM_CAPACITY_IN_USE",
                    "教室新容量低于关联开班容量");
        }
        mapper.updateRoom(
                id,
                schoolId,
                request.roomCode().strip(),
                request.roomName().strip(),
                trimToNull(request.location()),
                request.capacity(),
                request.status());
        return mapper.findRoomByCode(schoolId, request.roomCode().strip());
    }

    public List<Map<String, Object>> calendarEvents(
            Long requestedSchoolId, Long termId) {
        return mapper.listCalendarEvents(
                currentUser.optionalSchoolScope(requestedSchoolId), termId);
    }

    @Transactional
    public Map<String, Object> createCalendarEvent(
            AcademicController.CalendarEventRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        AcademicTerm term = requireCalendarTerm(request.termId(), request.eventDate());
        reconcileClosedCalendarDay(
                schoolId, request.eventDate(), request.dayType());
        mapper.insertCalendarEvent(
                schoolId,
                term.getId(),
                request.eventDate(),
                request.dayType(),
                request.eventName().strip(),
                trimToNull(request.description()),
                currentUser.principal().id());
        return mapper.findCalendarEventByDate(schoolId, request.eventDate());
    }

    @Transactional
    public Map<String, Object> updateCalendarEvent(
            long id, AcademicController.CalendarEventRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireCalendarTerm(request.termId(), request.eventDate());
        if (mapper.lockCalendarEvent(id, schoolId) == null) {
            throw ApiException.notFound("校历事件不存在或不在当前学校");
        }
        reconcileClosedCalendarDay(
                schoolId, request.eventDate(), request.dayType());
        mapper.updateCalendarEvent(
                id,
                schoolId,
                request.termId(),
                request.eventDate(),
                request.dayType(),
                request.eventName().strip(),
                trimToNull(request.description()));
        return mapper.findCalendarEvent(id, schoolId);
    }

    public List<Map<String, Object>> scheduleAdjustments(
            Long requestedSchoolId, Long offeringId) {
        PlatformPrincipal principal = currentUser.principal();
        Long teacherId = null;
        if ("TEACHER".equals(principal.roleCode())) {
            if (principal.teacherId() == null) {
                throw ApiException.forbidden("当前教师账号缺少教师档案");
            }
            teacherId = principal.teacherId();
        }
        return mapper.listScheduleAdjustments(
                currentUser.optionalSchoolScope(requestedSchoolId),
                offeringId,
                teacherId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reschedule(
            long sessionId, AcademicController.RescheduleRequest request) {
        if (!request.startTime().isBefore(request.endTime())) {
            throw ApiException.badRequest(
                    "INVALID_TIME_RANGE",
                    "调课开始时间必须早于结束时间");
        }
        SessionResource snapshot = mapper.findSessionResource(sessionId);
        if (snapshot == null) {
            throw ApiException.notFound("课次不存在");
        }
        long schoolId = currentUser.schoolScope(snapshot.getSchoolId());

        if (snapshot.getTermId() != null && mapper.lockTerm(snapshot.getTermId()) == null) {
            throw ApiException.notFound("课次所属学期不存在");
        }
        if (mapper.lockTeacher(snapshot.getTeacherId(), schoolId) == null) {
            throw ApiException.notFound("课次教师不存在或不在当前学校");
        }
        RoomResource room = lockTargetRoom(
                request.roomId(), schoolId, snapshot.getOfferingCapacity());
        if (mapper.lockOffering(snapshot.getOfferingId(), schoolId) == null) {
            throw ApiException.notFound("课次所属开班不存在或已变化");
        }
        List<Long> enrolledStudentIds =
                lockActiveEnrollmentStudentIds(snapshot.getOfferingId());
        SessionResource current = mapper.lockSessionResource(sessionId);
        if (current == null
                || current.getOfferingId() != snapshot.getOfferingId()
                || current.getTeacherId() != snapshot.getTeacherId()
                || current.getSchoolId() != schoolId) {
            throw ApiException.notFound("课次不存在或资源关联已变化");
        }
        validateScheduleTarget(
                current,
                request.sessionDate(),
                request.startTime(),
                request.endTime(),
                room.getId());
        validateScheduleMutation(
                current,
                schoolId,
                request.sessionDate(),
                request.startTime(),
                request.endTime(),
                room.getId(),
                enrolledStudentIds);

        long actorId = currentUser.principal().id();
        LocalDateTime appliedAt = LocalDateTime.now(clock);
        mapper.insertScheduleAdjustment(
                schoolId,
                sessionId,
                current.getSessionDate(),
                current.getStartTime(),
                current.getEndTime(),
                current.getRoomId(),
                current.getClassroom(),
                request.sessionDate(),
                request.startTime(),
                request.endTime(),
                room.getId(),
                room.getRoomName(),
                request.reason().strip(),
                actorId,
                appliedAt);
        if (mapper.updateSessionSchedule(
                        sessionId,
                        schoolId,
                        request.sessionDate(),
                        request.startTime(),
                        request.endTime(),
                        room.getId(),
                        room.getRoomName())
                != 1) {
            throw ApiException.conflict(
                    "SESSION_CHANGED",
                    "课次已被其他操作更新，请刷新后重试");
        }
        return mapper.findLatestScheduleAdjustment(sessionId, actorId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> revertScheduleAdjustment(long adjustmentId) {
        ScheduleAdjustmentResource snapshot =
                mapper.findScheduleAdjustmentResource(adjustmentId);
        if (snapshot == null) {
            throw ApiException.notFound("调课记录不存在");
        }
        long schoolId = currentUser.schoolScope(snapshot.getSchoolId());
        SessionResource sessionSnapshot = mapper.findSessionResource(snapshot.getSessionId());
        if (sessionSnapshot == null || sessionSnapshot.getSchoolId() != schoolId) {
            throw ApiException.notFound("调课关联课次不存在或不在当前学校");
        }

        // The mutation lock order is shared with reschedule and enrollment:
        // term -> teacher -> target room -> offering -> students -> lesson.
        // Acquiring shared student locks before either transaction locks its own
        // lesson prevents a second schedule mutation from forming a
        // student/lesson deadlock while its fresh conflict read runs.
        if (sessionSnapshot.getTermId() != null
                && mapper.lockTerm(sessionSnapshot.getTermId()) == null) {
            throw ApiException.notFound("课次所属学期不存在");
        }
        if (mapper.lockTeacher(sessionSnapshot.getTeacherId(), schoolId) == null) {
            throw ApiException.notFound("课次教师不存在或不在当前学校");
        }
        lockTargetRoomIfPresent(
                snapshot.getOriginalRoomId(),
                schoolId,
                sessionSnapshot.getOfferingCapacity());
        if (mapper.lockOffering(sessionSnapshot.getOfferingId(), schoolId) == null) {
            throw ApiException.notFound("课次所属开班不存在或已变化");
        }
        List<Long> enrolledStudentIds =
                lockActiveEnrollmentStudentIds(sessionSnapshot.getOfferingId());
        SessionResource current = mapper.lockSessionResource(snapshot.getSessionId());
        if (current == null
                || current.getOfferingId() != sessionSnapshot.getOfferingId()
                || current.getTeacherId() != sessionSnapshot.getTeacherId()
                || current.getSchoolId() != schoolId) {
            throw ApiException.notFound("课次不存在或资源关联已变化");
        }
        ScheduleAdjustmentResource adjustment =
                mapper.lockScheduleAdjustmentResource(adjustmentId, schoolId);
        if (adjustment == null || adjustment.getSessionId() != current.getId()) {
            throw ApiException.notFound("调课记录不存在或已变化");
        }
        if (!"APPLIED".equals(adjustment.getStatus())) {
            throw ApiException.conflict(
                    "SCHEDULE_ADJUSTMENT_NOT_ACTIVE",
                    "该调课记录已经撤销或不再生效");
        }
        ScheduleAdjustmentResource latest =
                mapper.lockLatestAppliedScheduleAdjustment(current.getId(), schoolId);
        if (latest == null || latest.getId() != adjustment.getId()) {
            throw ApiException.conflict(
                    "SCHEDULE_ADJUSTMENT_NOT_LATEST",
                    "只能撤销当前仍生效的最新调课记录");
        }
        if (!scheduleMatches(
                current,
                adjustment.getAdjustedSessionDate(),
                adjustment.getAdjustedStartTime(),
                adjustment.getAdjustedEndTime(),
                adjustment.getAdjustedRoomId())) {
            throw ApiException.conflict(
                    "SESSION_CHANGED",
                    "当前课次安排与调课记录不一致，请刷新后重试");
        }
        validateScheduleTarget(
                current,
                adjustment.getOriginalSessionDate(),
                adjustment.getOriginalStartTime(),
                adjustment.getOriginalEndTime(),
                adjustment.getOriginalRoomId());
        validateScheduleMutation(
                current,
                schoolId,
                adjustment.getOriginalSessionDate(),
                adjustment.getOriginalStartTime(),
                adjustment.getOriginalEndTime(),
                adjustment.getOriginalRoomId(),
                enrolledStudentIds);

        if (mapper.updateSessionSchedule(
                        current.getId(),
                        schoolId,
                        adjustment.getOriginalSessionDate(),
                        adjustment.getOriginalStartTime(),
                        adjustment.getOriginalEndTime(),
                        adjustment.getOriginalRoomId(),
                        adjustment.getOriginalClassroom())
                != 1) {
            throw ApiException.conflict(
                    "SESSION_CHANGED",
                    "课次已被其他操作更新，请刷新后重试");
        }
        if (mapper.markScheduleAdjustmentReverted(adjustment.getId(), schoolId) != 1) {
            throw ApiException.conflict(
                    "SCHEDULE_ADJUSTMENT_CHANGED",
                    "调课记录状态已变化，请刷新后重试");
        }
        return mapper.findScheduleAdjustmentView(adjustment.getId(), schoolId);
    }

    private RoomResource lockTargetRoom(
            long roomId, long schoolId, int offeringCapacity) {
        RoomResource room = mapper.lockRoom(roomId, schoolId);
        if (room == null || !"ACTIVE".equals(room.getStatus())) {
            throw ApiException.badRequest(
                    "INVALID_ROOM",
                    "教室不存在、不属于当前学校或已停用");
        }
        if (room.getCapacity() < offeringCapacity) {
            throw ApiException.badRequest(
                    "ROOM_CAPACITY_TOO_SMALL",
                    "教室容量不能低于开班容量");
        }
        return room;
    }

    private void lockTargetRoomIfPresent(
            Long roomId, long schoolId, int offeringCapacity) {
        if (roomId != null) {
            lockTargetRoom(roomId, schoolId, offeringCapacity);
        }
    }

    private List<Long> lockActiveEnrollmentStudentIds(long offeringId) {
        List<Long> studentIds = mapper.lockActiveEnrollmentStudentIds(offeringId);
        return studentIds == null ? List.of() : studentIds;
    }

    private void validateScheduleMutation(
            SessionResource current,
            long schoolId,
            LocalDate sessionDate,
            LocalTime startTime,
            LocalTime endTime,
            Long roomId,
            List<Long> enrolledStudentIds) {
        if (mapper.countSessionAttendance(current.getId()) > 0) {
            throw ApiException.conflict(
                    "SESSION_HAS_ATTENDANCE",
                    "已有考勤记录的课次不能调课");
        }
        if (mapper.countClosedCalendarDay(schoolId, sessionDate) > 0) {
            throw ApiException.conflict(
                    "CALENDAR_DAY_CLOSED",
                    "目标日期为放假或停课日");
        }
        if (mapper.findTeacherSessionConflict(
                        current.getTeacherId(),
                        current.getId(),
                        sessionDate,
                        startTime,
                        endTime)
                != null) {
            throw ApiException.conflict(
                    "TEACHER_SCHEDULE_CONFLICT",
                    "该教师在调课目标时段已有其他课次或开班");
        }
        if (roomId != null
                && mapper.findRoomSessionConflict(
                                schoolId,
                                roomId,
                                current.getId(),
                                sessionDate,
                                startTime,
                                endTime)
                        != null) {
            throw ApiException.conflict(
                    "ROOM_SCHEDULE_CONFLICT",
                    "该教室在调课目标时段已被占用");
        }
        for (Long studentId : enrolledStudentIds) {
            if (mapper.findStudentSessionConflict(
                            studentId,
                            current.getOfferingId(),
                            sessionDate,
                            startTime,
                            endTime)
                    != null) {
                throw ApiException.conflict(
                        "STUDENT_SCHEDULE_CONFLICT",
                        "调课后会与学生已报名的其他课程发生时间冲突");
            }
        }
    }

    private void reconcileClosedCalendarDay(
            long schoolId, LocalDate eventDate, String dayType) {
        if (!List.of("HOLIDAY", "SUSPENDED").contains(dayType)) {
            return;
        }

        // 课次生成、调课、请假与考勤都先锁开班；这里以相同顺序
        // 锁定本校开班和当日课次，避免校历写入期间又产生新课次或历史记录。
        mapper.lockCalendarOfferings(schoolId);
        List<SessionResource> sessions =
                mapper.lockCalendarSessions(schoolId, eventDate);
        LocalDateTime now = LocalDateTime.now(clock);

        for (SessionResource session : sessions) {
            if ("CANCELED".equals(session.getStatus())) {
                continue;
            }
            boolean futureScheduled = "SCHEDULED".equals(session.getStatus())
                    && now.isBefore(LocalDateTime.of(
                            session.getSessionDate(), session.getStartTime()));
            if (!futureScheduled
                    || mapper.countCalendarSessionHistory(
                                    schoolId, session.getId())
                            > 0) {
                throw ApiException.conflict(
                        "CALENDAR_SESSION_CONFLICT",
                        "该日期存在已开始、已完成或已有业务历史的课次，不能设为放假或停课日");
            }
        }

        for (SessionResource session : sessions) {
            if ("SCHEDULED".equals(session.getStatus())
                    && mapper.cancelCalendarSession(
                                    schoolId, session.getId(), now)
                            != 1) {
                throw ApiException.conflict(
                        "CALENDAR_SESSION_CONFLICT",
                        "课次状态已变化，校历事件未保存，请刷新后重试");
            }
        }
    }

    private AcademicTerm requireWritableTerm(long termId) {
        AcademicTerm term = mapper.lockTerm(termId);
        if (term == null) {
            throw ApiException.badRequest("INVALID_TERM", "学期不存在");
        }
        if (List.of("CLOSED", "ARCHIVED").contains(term.getStatus())) {
            throw ApiException.conflict(
                    "TERM_NOT_WRITABLE",
                    "已结束或归档学期不能维护服务计划");
        }
        return term;
    }

    private AcademicTerm requireCalendarTerm(long termId, LocalDate eventDate) {
        AcademicTerm term = mapper.lockTerm(termId);
        if (term == null) {
            throw ApiException.badRequest("INVALID_TERM", "学期不存在");
        }
        if ("ARCHIVED".equals(term.getStatus())) {
            throw ApiException.conflict(
                    "TERM_ARCHIVED",
                    "归档学期的校历不能修改");
        }
        if (eventDate.isBefore(term.getStartDate())
                || eventDate.isAfter(term.getEndDate())) {
            throw ApiException.badRequest(
                    "CALENDAR_DATE_OUTSIDE_TERM",
                    "校历日期必须位于所属学期日期范围内");
        }
        return term;
    }

    private void validateScheduleTarget(
            SessionResource current,
            LocalDate sessionDate,
            LocalTime startTime,
            LocalTime endTime,
            Long roomId) {
        if (!startTime.isBefore(endTime)) {
            throw ApiException.badRequest(
                    "INVALID_TIME_RANGE",
                    "调课开始时间必须早于结束时间");
        }
        if (!"SCHEDULED".equals(current.getStatus())) {
            throw ApiException.conflict(
                    "SESSION_NOT_RESCHEDULABLE",
                    "只有待上课课次可以调课");
        }
        if (!List.of("PUBLISHED", "CLOSED").contains(current.getOfferingStatus())) {
            throw ApiException.conflict(
                    "OFFERING_NOT_RESCHEDULABLE",
                    "只有已发布或已关闭报名的开班可以调课");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(LocalDateTime.of(current.getSessionDate(), current.getStartTime()))) {
            throw ApiException.conflict(
                    "SESSION_ALREADY_STARTED",
                    "已开始的课次不能调课");
        }
        if (!now.isBefore(LocalDateTime.of(sessionDate, startTime))) {
            throw ApiException.badRequest(
                    "RESCHEDULE_IN_PAST",
                    "调课后的开始时间必须晚于当前时间");
        }
        LocalDate lower = current.getTermStartDate() == null
                ? current.getOfferingStartDate()
                : current.getTermStartDate();
        LocalDate upper = current.getTermEndDate() == null
                ? current.getOfferingEndDate()
                : current.getTermEndDate();
        if (sessionDate.isBefore(lower)
                || sessionDate.isAfter(upper)) {
            throw ApiException.badRequest(
                    "RESCHEDULE_DATE_OUT_OF_RANGE",
                    "调课日期必须位于开班所属学期或原开班日期范围内");
        }
        boolean unchanged = scheduleMatches(
                current, sessionDate, startTime, endTime, roomId);
        if (unchanged) {
            throw ApiException.badRequest(
                    "NO_SCHEDULE_CHANGE",
                    "调课后的日期、时间和教室与当前安排完全相同");
        }
    }

    private boolean scheduleMatches(
            SessionResource current,
            LocalDate sessionDate,
            LocalTime startTime,
            LocalTime endTime,
            Long roomId) {
        return Objects.equals(current.getSessionDate(), sessionDate)
                && Objects.equals(current.getStartTime(), startTime)
                && Objects.equals(current.getEndTime(), endTime)
                && Objects.equals(current.getRoomId(), roomId);
    }

    private void validateTermRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw ApiException.badRequest(
                    "INVALID_TERM_RANGE",
                    "学期开始日期不能晚于结束日期");
        }
    }

    private boolean isClosedOrArchived(String status) {
        return "CLOSED".equals(status) || "ARCHIVED".equals(status);
    }

    private void validateTermTransition(String current, String target) {
        boolean allowed = switch (current) {
            case "DRAFT" -> List.of("DRAFT", "ACTIVE").contains(target);
            case "ACTIVE" -> List.of("ACTIVE", "CLOSED").contains(target);
            case "CLOSED" -> List.of("CLOSED", "ARCHIVED").contains(target);
            case "ARCHIVED" -> "ARCHIVED".equals(target);
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict(
                    "INVALID_TERM_TRANSITION",
                    "学期状态不能从 " + current + " 变更为 " + target);
        }
    }

    private void validatePlanTransition(String role, String current, String target) {
        boolean allowed;
        if ("SCHOOL_ADMIN".equals(role)) {
            allowed = List.of("DRAFT", "RETURNED").contains(current)
                    && "SUBMITTED".equals(target);
        } else if ("REGULATOR".equals(role)) {
            allowed = switch (current) {
                case "SUBMITTED" -> List.of("FILED", "RETURNED").contains(target);
                case "FILED" -> "ACTIVE".equals(target);
                case "ACTIVE" -> "CLOSED".equals(target);
                case "CLOSED" -> "ARCHIVED".equals(target);
                default -> false;
            };
        } else {
            allowed = false;
        }
        if (!allowed) {
            throw ApiException.conflict(
                    "INVALID_PLAN_TRANSITION",
                    "当前角色不能将服务计划从 " + current + " 变更为 " + target);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
