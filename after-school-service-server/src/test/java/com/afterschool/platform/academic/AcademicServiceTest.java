package com.afterschool.platform.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class AcademicServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 31, 18, 0);

    private AcademicMapper mapper;
    private CurrentUser currentUser;
    private PlatformPrincipal principal;
    private AcademicService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AcademicMapper.class);
        currentUser = mock(CurrentUser.class);
        principal = mock(PlatformPrincipal.class);
        when(principal.id()).thenReturn(8L);
        when(currentUser.principal()).thenReturn(principal);
        service = new AcademicService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-07-31T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void rejectsSkippedTermTransition() {
        AcademicTerm term = term("DRAFT");
        when(mapper.lockTerm(1)).thenReturn(term);

        AcademicController.TermRequest request = new AcademicController.TermRequest(
                "2026-FALL",
                "2026 秋季学期",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                "CLOSED");

        assertCode(
                "INVALID_TERM_TRANSITION",
                () -> service.updateTerm(1, request));

        verify(mapper, never()).updateTerm(
                1,
                "2026-FALL",
                "2026 秋季学期",
                request.startDate(),
                request.endDate(),
                "CLOSED");
    }

    @Test
    void schoolCanResubmitReturnedPlan() {
        ServicePlan plan = plan("RETURNED");
        when(mapper.findServicePlan(10)).thenReturn(plan);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockServicePlan(10, 1)).thenReturn(plan);
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        when(mapper.transitionServicePlan(
                        10,
                        1,
                        "RETURNED",
                        "SUBMITTED",
                        null,
                        8,
                        NOW))
                .thenReturn(1);

        service.transitionServicePlan(
                10,
                new AcademicController.PlanTransitionRequest("SUBMITTED", null));

        verify(mapper).transitionServicePlan(
                10,
                1,
                "RETURNED",
                "SUBMITTED",
                null,
                8,
                NOW);
    }

    @Test
    void schoolCannotFileItsOwnPlan() {
        ServicePlan plan = plan("SUBMITTED");
        when(mapper.findServicePlan(10)).thenReturn(plan);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockServicePlan(10, 1)).thenReturn(plan);
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");

        assertCode(
                "INVALID_PLAN_TRANSITION",
                () -> service.transitionServicePlan(
                        10,
                        new AcademicController.PlanTransitionRequest("FILED", null)));
    }

    @Test
    void regulatorMustGiveReasonWhenReturningPlan() {
        ServicePlan plan = plan("SUBMITTED");
        when(mapper.findServicePlan(10)).thenReturn(plan);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockServicePlan(10, 1)).thenReturn(plan);
        when(principal.roleCode()).thenReturn("REGULATOR");

        assertCode(
                "RETURN_REASON_REQUIRED",
                () -> service.transitionServicePlan(
                        10,
                        new AcademicController.PlanTransitionRequest("RETURNED", "  ")));
    }

    @Test
    void filedPlanCannotActivateBeforeTerm() {
        ServicePlan plan = plan("FILED");
        when(mapper.findServicePlan(10)).thenReturn(plan);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("DRAFT"));
        when(mapper.lockServicePlan(10, 1)).thenReturn(plan);
        when(principal.roleCode()).thenReturn("REGULATOR");

        assertCode(
                "TERM_NOT_ACTIVE",
                () -> service.transitionServicePlan(
                        10,
                        new AcademicController.PlanTransitionRequest("ACTIVE", null)));
    }

    @Test
    void creatingClosedCalendarDayCancelsFutureCleanSessionsBeforeWritingEvent() {
        LocalDate eventDate = LocalDate.of(2026, 9, 2);
        SessionResource future = session();
        SessionResource alreadyCanceled = session();
        alreadyCanceled.setId(101);
        alreadyCanceled.setStatus("CANCELED");
        when(currentUser.schoolScope(null)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockCalendarOfferings(1)).thenReturn(List.of(40L));
        when(mapper.lockCalendarSessions(1, eventDate))
                .thenReturn(List.of(future, alreadyCanceled));
        when(mapper.countCalendarSessionHistory(1, 100)).thenReturn(0);
        when(mapper.cancelCalendarSession(1, 100, NOW)).thenReturn(1);
        when(mapper.findCalendarEventByDate(1, eventDate))
                .thenReturn(Map.of("id", 500L));

        service.createCalendarEvent(new AcademicController.CalendarEventRequest(
                null,
                1,
                eventDate,
                "HOLIDAY",
                "国庆调休",
                null));

        InOrder writes = inOrder(mapper);
        writes.verify(mapper).lockTerm(1);
        writes.verify(mapper).lockCalendarOfferings(1);
        writes.verify(mapper).lockCalendarSessions(1, eventDate);
        writes.verify(mapper).countCalendarSessionHistory(1, 100);
        writes.verify(mapper).cancelCalendarSession(1, 100, NOW);
        writes.verify(mapper).insertCalendarEvent(
                1, 1, eventDate, "HOLIDAY", "国庆调休", null, 8);
        verify(mapper, never()).cancelCalendarSession(1, 101, NOW);
    }

    @Test
    void rejectsClosedCalendarDayWhenSessionHasBusinessHistory() {
        LocalDate eventDate = LocalDate.of(2026, 9, 2);
        when(currentUser.schoolScope(null)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockCalendarOfferings(1)).thenReturn(List.of(40L));
        when(mapper.lockCalendarSessions(1, eventDate)).thenReturn(List.of(session()));
        when(mapper.countCalendarSessionHistory(1, 100)).thenReturn(1);

        assertCode(
                "CALENDAR_SESSION_CONFLICT",
                () -> service.createCalendarEvent(new AcademicController.CalendarEventRequest(
                        null,
                        1,
                        eventDate,
                        "SUSPENDED",
                        "临时停课",
                        null)));

        verify(mapper, never()).cancelCalendarSession(1, 100, NOW);
        verify(mapper, never()).insertCalendarEvent(
                1, 1, eventDate, "SUSPENDED", "临时停课", null, 8);
    }

    @Test
    void rejectsClosedCalendarDayWhenSessionHasAlreadyStarted() {
        LocalDate eventDate = LocalDate.of(2026, 7, 31);
        SessionResource started = session();
        started.setSessionDate(eventDate);
        started.setStartTime(LocalTime.of(17, 30));
        AcademicTerm currentTerm = term("ACTIVE");
        currentTerm.setStartDate(LocalDate.of(2026, 7, 1));
        when(currentUser.schoolScope(null)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(currentTerm);
        when(mapper.lockCalendarOfferings(1)).thenReturn(List.of(40L));
        when(mapper.lockCalendarSessions(1, eventDate)).thenReturn(List.of(started));

        assertCode(
                "CALENDAR_SESSION_CONFLICT",
                () -> service.createCalendarEvent(new AcademicController.CalendarEventRequest(
                        null,
                        1,
                        eventDate,
                        "HOLIDAY",
                        "停课",
                        null)));

        verify(mapper, never()).countCalendarSessionHistory(1, 100);
        verify(mapper, never()).cancelCalendarSession(1, 100, NOW);
        verify(mapper, never()).insertCalendarEvent(
                1, 1, eventDate, "HOLIDAY", "停课", null, 8);
    }

    @Test
    void updatingEventToClosedDayReconcilesSessionsBeforeChangingCalendar() {
        LocalDate eventDate = LocalDate.of(2026, 9, 2);
        when(currentUser.schoolScope(null)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockCalendarEvent(500, 1)).thenReturn(500L);
        when(mapper.lockCalendarOfferings(1)).thenReturn(List.of(40L));
        when(mapper.lockCalendarSessions(1, eventDate)).thenReturn(List.of(session()));
        when(mapper.countCalendarSessionHistory(1, 100)).thenReturn(0);
        when(mapper.cancelCalendarSession(1, 100, NOW)).thenReturn(1);
        when(mapper.findCalendarEvent(500, 1)).thenReturn(Map.of("id", 500L));

        service.updateCalendarEvent(
                500,
                new AcademicController.CalendarEventRequest(
                        null,
                        1,
                        eventDate,
                        "SUSPENDED",
                        "临时停课",
                        null));

        InOrder writes = inOrder(mapper);
        writes.verify(mapper).lockTerm(1);
        writes.verify(mapper).lockCalendarEvent(500, 1);
        writes.verify(mapper).lockCalendarOfferings(1);
        writes.verify(mapper).lockCalendarSessions(1, eventDate);
        writes.verify(mapper).countCalendarSessionHistory(1, 100);
        writes.verify(mapper).cancelCalendarSession(1, 100, NOW);
        writes.verify(mapper).updateCalendarEvent(
                500, 1, 1, eventDate, "SUSPENDED", "临时停课", null);
    }

    @Test
    void teachingDayDoesNotTouchExistingSessions() {
        LocalDate eventDate = LocalDate.of(2026, 9, 2);
        when(currentUser.schoolScope(null)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.findCalendarEventByDate(1, eventDate))
                .thenReturn(Map.of("id", 500L));

        service.createCalendarEvent(new AcademicController.CalendarEventRequest(
                null,
                1,
                eventDate,
                "TEACHING_DAY",
                "正常上课",
                null));

        verify(mapper, never()).lockCalendarOfferings(1);
        verify(mapper, never()).lockCalendarSessions(1, eventDate);
        verify(mapper, never()).cancelCalendarSession(1, 100, NOW);
        verify(mapper).insertCalendarEvent(
                1, 1, eventDate, "TEACHING_DAY", "正常上课", null, 8);
    }

    @Test
    void rescheduleLocksResourcesInStableOrderAndWritesAuditBeforeSession() {
        SessionResource session = session();
        RoomResource room = room();
        when(mapper.findSessionResource(100)).thenReturn(session);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockTeacher(20, 1)).thenReturn(20L);
        when(mapper.lockRoom(30, 1)).thenReturn(room);
        when(mapper.lockOffering(40, 1)).thenReturn(40L);
        when(mapper.lockSessionResource(100)).thenReturn(session);
        when(mapper.lockActiveEnrollmentStudentIds(40)).thenReturn(List.of());
        when(mapper.findTeacherSessionConflict(
                        20,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(null);
        when(mapper.findRoomSessionConflict(
                        1,
                        30L,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(null);
        when(mapper.updateSessionSchedule(
                        100,
                        1,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0),
                        30L,
                        "创客教室"))
                .thenReturn(1);
        when(mapper.findLatestScheduleAdjustment(100, 8))
                .thenReturn(Map.of("id", 900L));

        service.reschedule(
                100,
                new AcademicController.RescheduleRequest(
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0),
                        30,
                        "参加区级活动"));

        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockTerm(1);
        locks.verify(mapper).lockTeacher(20, 1);
        locks.verify(mapper).lockRoom(30, 1);
        locks.verify(mapper).lockOffering(40, 1);
        locks.verify(mapper).lockActiveEnrollmentStudentIds(40);
        locks.verify(mapper).lockSessionResource(100);
        locks.verify(mapper).insertScheduleAdjustment(
                1,
                100,
                LocalDate.of(2026, 9, 2),
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                30L,
                "创客教室",
                LocalDate.of(2026, 9, 9),
                LocalTime.of(15, 0),
                LocalTime.of(16, 0),
                30L,
                "创客教室",
                "参加区级活动",
                8,
                NOW);
        locks.verify(mapper).updateSessionSchedule(
                100,
                1,
                LocalDate.of(2026, 9, 9),
                LocalTime.of(15, 0),
                LocalTime.of(16, 0),
                30L,
                "创客教室");
    }

    @Test
    void rejectsRoomConflictWithoutWritingAdjustment() {
        SessionResource session = session();
        when(mapper.findSessionResource(100)).thenReturn(session);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockTeacher(20, 1)).thenReturn(20L);
        when(mapper.lockRoom(30, 1)).thenReturn(room());
        when(mapper.lockOffering(40, 1)).thenReturn(40L);
        when(mapper.lockSessionResource(100)).thenReturn(session);
        when(mapper.lockActiveEnrollmentStudentIds(40)).thenReturn(List.of());
        when(mapper.findTeacherSessionConflict(
                        20,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(null);
        when(mapper.findRoomSessionConflict(
                        1,
                        30,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(101L);

        assertCode(
                "ROOM_SCHEDULE_CONFLICT",
                () -> service.reschedule(
                        100,
                        new AcademicController.RescheduleRequest(
                                LocalDate.of(2026, 9, 9),
                                LocalTime.of(15, 0),
                                LocalTime.of(16, 0),
                                30,
                                "参加区级活动")));

        verify(mapper, never()).insertScheduleAdjustment(
                1,
                100,
                session.getSessionDate(),
                session.getStartTime(),
                session.getEndTime(),
                session.getRoomId(),
                session.getClassroom(),
                LocalDate.of(2026, 9, 9),
                LocalTime.of(15, 0),
                LocalTime.of(16, 0),
                30,
                "创客教室",
                "参加区级活动",
                8,
                NOW);
    }

    @Test
    void rejectsRescheduleThatWouldCreateAnEnrolledStudentsActualCourseConflict() {
        SessionResource session = session();
        configureReschedulePrerequisites(session);
        when(mapper.lockActiveEnrollmentStudentIds(40)).thenReturn(List.of(10L));
        when(mapper.findTeacherSessionConflict(
                        20,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(null);
        when(mapper.findRoomSessionConflict(
                        1,
                        30,
                        100,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(null);
        when(mapper.findStudentSessionConflict(
                        10,
                        40,
                        LocalDate.of(2026, 9, 9),
                        LocalTime.of(15, 0),
                        LocalTime.of(16, 0)))
                .thenReturn(501L);

        assertCode(
                "STUDENT_SCHEDULE_CONFLICT",
                () -> service.reschedule(100, rescheduleRequest()));

        verify(mapper, never()).insertScheduleAdjustment(
                1,
                100,
                session.getSessionDate(),
                session.getStartTime(),
                session.getEndTime(),
                session.getRoomId(),
                session.getClassroom(),
                LocalDate.of(2026, 9, 9),
                LocalTime.of(15, 0),
                LocalTime.of(16, 0),
                30,
                "创客教室",
                "参加区级活动",
                8,
                NOW);
    }

    @Test
    void revertsTheLatestActiveAdjustmentAfterRevalidatingResourcesAndStudents() {
        SessionResource current = adjustedSession();
        ScheduleAdjustmentResource adjustment = adjustment("APPLIED");
        configureRevertPrerequisites(current, adjustment);
        when(mapper.lockLatestAppliedScheduleAdjustment(100, 1)).thenReturn(adjustment);
        when(mapper.lockActiveEnrollmentStudentIds(40)).thenReturn(List.of());
        when(mapper.findTeacherSessionConflict(
                        20,
                        100,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30)))
                .thenReturn(null);
        when(mapper.findRoomSessionConflict(
                        1,
                        30,
                        100,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30)))
                .thenReturn(null);
        when(mapper.updateSessionSchedule(
                        100,
                        1,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        30L,
                        "创客教室"))
                .thenReturn(1);
        when(mapper.markScheduleAdjustmentReverted(900, 1)).thenReturn(1);
        when(mapper.findScheduleAdjustmentView(900, 1)).thenReturn(Map.of("id", 900L));

        service.revertScheduleAdjustment(900);

        InOrder writes = inOrder(mapper);
        writes.verify(mapper).lockTerm(1);
        writes.verify(mapper).lockTeacher(20, 1);
        writes.verify(mapper).lockRoom(30, 1);
        writes.verify(mapper).lockOffering(40, 1);
        writes.verify(mapper).lockActiveEnrollmentStudentIds(40);
        writes.verify(mapper).lockSessionResource(100);
        writes.verify(mapper).updateSessionSchedule(
                100,
                1,
                LocalDate.of(2026, 9, 2),
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                30L,
                "创客教室");
        writes.verify(mapper).markScheduleAdjustmentReverted(900, 1);
    }

    @Test
    void rejectsRevertingANonLatestActiveAdjustment() {
        SessionResource current = adjustedSession();
        ScheduleAdjustmentResource adjustment = adjustment("APPLIED");
        ScheduleAdjustmentResource later = adjustment("APPLIED");
        later.setId(901);
        configureRevertPrerequisites(current, adjustment);
        when(mapper.lockLatestAppliedScheduleAdjustment(100, 1)).thenReturn(later);

        assertCode(
                "SCHEDULE_ADJUSTMENT_NOT_LATEST",
                () -> service.revertScheduleAdjustment(900));

        verify(mapper, never()).updateSessionSchedule(
                100,
                1,
                adjustment.getOriginalSessionDate(),
                adjustment.getOriginalStartTime(),
                adjustment.getOriginalEndTime(),
                adjustment.getOriginalRoomId(),
                adjustment.getOriginalClassroom());
        verify(mapper, never()).markScheduleAdjustmentReverted(900, 1);
    }

    @Test
    void rejectsRevertingAnAlreadyRevertedAdjustment() {
        SessionResource current = adjustedSession();
        ScheduleAdjustmentResource adjustment = adjustment("REVERTED");
        configureRevertPrerequisites(current, adjustment);

        assertCode(
                "SCHEDULE_ADJUSTMENT_NOT_ACTIVE",
                () -> service.revertScheduleAdjustment(900));

        verify(mapper, never()).lockLatestAppliedScheduleAdjustment(100, 1);
        verify(mapper, never()).markScheduleAdjustmentReverted(900, 1);
    }

    @Test
    void rejectsRevertingWhenTheRestoredScheduleWouldConflictWithAnEnrolledStudent() {
        SessionResource current = adjustedSession();
        ScheduleAdjustmentResource adjustment = adjustment("APPLIED");
        configureRevertPrerequisites(current, adjustment);
        when(mapper.lockLatestAppliedScheduleAdjustment(100, 1)).thenReturn(adjustment);
        when(mapper.lockActiveEnrollmentStudentIds(40)).thenReturn(List.of(10L));
        when(mapper.findTeacherSessionConflict(
                        20,
                        100,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30)))
                .thenReturn(null);
        when(mapper.findRoomSessionConflict(
                        1,
                        30,
                        100,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30)))
                .thenReturn(null);
        when(mapper.findStudentSessionConflict(
                        10,
                        40,
                        LocalDate.of(2026, 9, 2),
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30)))
                .thenReturn(501L);

        assertCode(
                "STUDENT_SCHEDULE_CONFLICT",
                () -> service.revertScheduleAdjustment(900));

        verify(mapper, never()).markScheduleAdjustmentReverted(900, 1);
    }

    @Test
    void scheduleMutationsDeclareReadCommittedForDirectServiceCalls()
            throws Exception {
        Transactional reschedule = AcademicService.class
                .getMethod(
                        "reschedule",
                        long.class,
                        AcademicController.RescheduleRequest.class)
                .getAnnotation(Transactional.class);
        Transactional revert = AcademicService.class
                .getMethod("revertScheduleAdjustment", long.class)
                .getAnnotation(Transactional.class);

        assertThat(reschedule).isNotNull();
        assertThat(revert).isNotNull();
        assertThat(reschedule.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(revert.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private void configureReschedulePrerequisites(SessionResource session) {
        when(mapper.findSessionResource(100)).thenReturn(session);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockTeacher(20, 1)).thenReturn(20L);
        when(mapper.lockRoom(30, 1)).thenReturn(room());
        when(mapper.lockOffering(40, 1)).thenReturn(40L);
        when(mapper.lockSessionResource(100)).thenReturn(session);
    }

    private void configureRevertPrerequisites(
            SessionResource current, ScheduleAdjustmentResource adjustment) {
        when(mapper.findScheduleAdjustmentResource(900)).thenReturn(adjustment);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.findSessionResource(100)).thenReturn(current);
        when(mapper.lockTerm(1)).thenReturn(term("ACTIVE"));
        when(mapper.lockTeacher(20, 1)).thenReturn(20L);
        when(mapper.lockRoom(30, 1)).thenReturn(room());
        when(mapper.lockOffering(40, 1)).thenReturn(40L);
        when(mapper.lockSessionResource(100)).thenReturn(current);
        when(mapper.lockScheduleAdjustmentResource(900, 1)).thenReturn(adjustment);
    }

    private AcademicController.RescheduleRequest rescheduleRequest() {
        return new AcademicController.RescheduleRequest(
                LocalDate.of(2026, 9, 9),
                LocalTime.of(15, 0),
                LocalTime.of(16, 0),
                30,
                "参加区级活动");
    }

    private SessionResource adjustedSession() {
        SessionResource session = session();
        session.setSessionDate(LocalDate.of(2026, 9, 9));
        session.setStartTime(LocalTime.of(15, 0));
        session.setEndTime(LocalTime.of(16, 0));
        return session;
    }

    private ScheduleAdjustmentResource adjustment(String status) {
        ScheduleAdjustmentResource adjustment = new ScheduleAdjustmentResource();
        adjustment.setId(900);
        adjustment.setSchoolId(1);
        adjustment.setSessionId(100);
        adjustment.setOriginalSessionDate(LocalDate.of(2026, 9, 2));
        adjustment.setOriginalStartTime(LocalTime.of(16, 30));
        adjustment.setOriginalEndTime(LocalTime.of(17, 30));
        adjustment.setOriginalRoomId(30L);
        adjustment.setOriginalClassroom("创客教室");
        adjustment.setAdjustedSessionDate(LocalDate.of(2026, 9, 9));
        adjustment.setAdjustedStartTime(LocalTime.of(15, 0));
        adjustment.setAdjustedEndTime(LocalTime.of(16, 0));
        adjustment.setAdjustedRoomId(30L);
        adjustment.setAdjustedClassroom("创客教室");
        adjustment.setStatus(status);
        return adjustment;
    }

    private AcademicTerm term(String status) {
        AcademicTerm term = new AcademicTerm();
        term.setId(1);
        term.setTermCode("2026-FALL");
        term.setTermName("2026 秋季学期");
        term.setStartDate(LocalDate.of(2026, 9, 1));
        term.setEndDate(LocalDate.of(2027, 1, 31));
        term.setStatus(status);
        return term;
    }

    private ServicePlan plan(String status) {
        ServicePlan plan = new ServicePlan();
        plan.setId(10);
        plan.setSchoolId(1);
        plan.setTermId(1);
        plan.setPlanCode("PLAN-2026-FALL");
        plan.setPlanName("秋季课后服务计划");
        plan.setStatus(status);
        return plan;
    }

    private RoomResource room() {
        RoomResource room = new RoomResource();
        room.setId(30);
        room.setSchoolId(1);
        room.setRoomCode("ROOM-MAKER");
        room.setRoomName("创客教室");
        room.setCapacity(30);
        room.setStatus("ACTIVE");
        return room;
    }

    private SessionResource session() {
        SessionResource session = new SessionResource();
        session.setId(100);
        session.setSchoolId(1);
        session.setOfferingId(40);
        session.setTeacherId(20);
        session.setTermId(1L);
        session.setTermStartDate(LocalDate.of(2026, 9, 1));
        session.setTermEndDate(LocalDate.of(2027, 1, 31));
        session.setOfferingStartDate(LocalDate.of(2026, 9, 1));
        session.setOfferingEndDate(LocalDate.of(2027, 1, 31));
        session.setOfferingCapacity(20);
        session.setSessionDate(LocalDate.of(2026, 9, 2));
        session.setStartTime(LocalTime.of(16, 30));
        session.setEndTime(LocalTime.of(17, 30));
        session.setRoomId(30L);
        session.setClassroom("创客教室");
        session.setStatus("SCHEDULED");
        session.setOfferingStatus("PUBLISHED");
        return session;
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }
}
