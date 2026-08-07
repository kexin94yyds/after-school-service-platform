package com.afterschool.platform.course;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.academic.AcademicMapper;
import com.afterschool.platform.academic.AcademicTerm;
import com.afterschool.platform.academic.RoomResource;
import com.afterschool.platform.academic.ServicePlan;
import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class CourseServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 31, 18, 0);

    private CourseMapper mapper;
    private AcademicMapper academicMapper;
    private CurrentUser currentUser;
    private CourseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CourseMapper.class);
        academicMapper = mock(AcademicMapper.class);
        currentUser = mock(CurrentUser.class);
        PlatformPrincipal principal = mock(PlatformPrincipal.class);
        when(principal.id()).thenReturn(1L);
        when(currentUser.principal()).thenReturn(principal);
        service = new CourseService(
                mapper,
                academicMapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-07-31T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void rejectsInvalidOfferingStatusTransition() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTeacher(1, 1)).thenReturn(1L);
        when(mapper.lockActiveCourse(1, 1)).thenReturn(1L);
        when(mapper.offeringStatus(10, 1)).thenReturn("DRAFT");

        assertCode(
                "INVALID_OFFERING_TRANSITION",
                () -> service.updateOffering(10, request("FINISHED")));
    }

    @Test
    void freezesScheduleAfterEnrollmentOrSessionExists() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.offeringStatus(10, 1)).thenReturn("PUBLISHED");
        when(mapper.lockActiveCourse(1, 1)).thenReturn(1L);
        when(mapper.teacherExists(1, 1)).thenReturn(1);
        when(mapper.lockTeacher(1, 1)).thenReturn(1L);
        when(mapper.countOfferingDependencies(10)).thenReturn(1);
        when(mapper.countOfferingShapeDifferences(
                        10,
                        1,
                        1,
                        1,
                        "O-TEST",
                        "2026-2027-1",
                        2,
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31),
                        LocalDateTime.of(2026, 8, 1, 8, 0),
                        LocalDateTime.of(2026, 8, 31, 18, 0),
                        "美术教室",
                        null,
                        null,
                        null))
                .thenReturn(1);

        assertCode(
                "OFFERING_SCHEDULE_FROZEN",
                () -> service.updateOffering(10, request("CLOSED")));
    }

    @Test
    void rejectsInvalidTimeRangeBeforeDatabaseAccess() {
        CourseController.OfferingRequest invalid = new CourseController.OfferingRequest(
                1L,
                1,
                1,
                "O-TEST",
                "2026-2027-1",
                2,
                LocalTime.of(17, 30),
                LocalTime.of(16, 30),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 31, 18, 0),
                20,
                "美术教室",
                "DRAFT",
                null,
                null,
                null);

        assertCode("INVALID_TIME_RANGE", () -> service.createOffering(invalid));
    }

    @Test
    void freezesCourseCodeAndGradeRulesAfterOfferingExists() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockCourse(7, 1)).thenReturn(7L);
        when(mapper.countCourseDependencies(7)).thenReturn(1);
        when(mapper.countCourseRuleDifferences(7, 1, "C-CHANGED", 2, 6))
                .thenReturn(1);

        CourseController.CourseRequest changed = new CourseController.CourseRequest(
                1L,
                "C-CHANGED",
                "课程名称",
                "综合实践",
                "说明",
                2,
                6,
                20,
                "ACTIVE");

        assertCode(
                "COURSE_RULES_FROZEN",
                () -> service.updateCourse(7, changed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "CLOSED"})
    void cancelingOpenOfferingCancelsEnrollmentsAndScheduledSessions(String currentStatus) {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTeacher(1, 1)).thenReturn(1L);
        when(mapper.offeringStatus(10, 1)).thenReturn(currentStatus);
        when(mapper.lockActiveCourse(1, 1)).thenReturn(1L);
        when(mapper.findTeacherConflict(
                        1,
                        10L,
                        2,
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31)))
                .thenReturn(null);
        when(mapper.updateOffering(
                        10,
                        1,
                        1,
                        1,
                        "O-TEST",
                        "2026-2027-1",
                        2,
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31),
                        LocalDateTime.of(2026, 8, 1, 8, 0),
                        LocalDateTime.of(2026, 8, 31, 18, 0),
                        20,
                        "美术教室",
                        "CANCELED",
                        null,
                        null,
                        null))
                .thenReturn(1);

        service.updateOffering(10, request("CANCELED"));

        InOrder updates = inOrder(mapper);
        updates.verify(mapper).offeringStatus(10, 1);
        updates.verify(mapper).updateOffering(
                10,
                1,
                1,
                1,
                "O-TEST",
                "2026-2027-1",
                2,
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 31, 18, 0),
                20,
                "美术教室",
                "CANCELED",
                null,
                null,
                null);
        updates.verify(mapper).cancelActiveEnrollments(10, 1);
        updates.verify(mapper).cancelScheduledSessions(10, NOW);
    }

    @Test
    void cannotFinishWhileScheduledSessionsRemain() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.lockTeacher(1, 1)).thenReturn(1L);
        when(mapper.offeringStatus(10, 1)).thenReturn("CLOSED");
        when(mapper.lockActiveCourse(1, 1)).thenReturn(1L);
        when(mapper.countScheduledSessions(10)).thenReturn(1);

        assertCode(
                "OFFERING_HAS_SCHEDULED_SESSIONS",
                () -> service.updateOffering(10, request("FINISHED")));

        verify(mapper, never()).cancelActiveEnrollments(10, 1);
        verify(mapper, never()).cancelScheduledSessions(10, NOW);
    }

    @Test
    void cannotCancelOfferingWithStartedUnresolvedSession() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(mapper.offeringStatus(10, 1)).thenReturn("PUBLISHED");
        when(mapper.countStartedScheduledSessions(10, NOW)).thenReturn(1);

        assertCode(
                "OFFERING_HAS_UNRESOLVED_SESSIONS",
                () -> service.updateOffering(10, request("CANCELED")));

        verify(mapper, never()).cancelActiveEnrollments(10, 1);
        verify(mapper, never()).cancelScheduledSessions(10, NOW);
    }

    @Test
    void cannotPublishOfferingBeforeLinkedPlanIsFiled() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(academicMapper.lockTerm(2)).thenReturn(term());
        ServicePlan plan = plan("SUBMITTED");
        when(academicMapper.lockServicePlan(3, 1)).thenReturn(plan);

        assertCode(
                "PLAN_NOT_FILED",
                () -> service.createOffering(linkedRequest("PUBLISHED")));

        verify(mapper, never()).lockTeacher(1, 1);
        verify(mapper, never()).insertOffering(
                1,
                1,
                1,
                "O-TEST",
                "2026-2027-1",
                2,
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 31, 18, 0),
                20,
                "创客教室",
                "PUBLISHED",
                2L,
                3L,
                4L);
    }

    @Test
    void rejectsRoomConflictForFiledPlanOffering() {
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(academicMapper.lockTerm(2)).thenReturn(term());
        when(academicMapper.lockServicePlan(3, 1)).thenReturn(plan("FILED"));
        when(mapper.lockTeacher(1, 1)).thenReturn(1L);
        when(academicMapper.lockRoom(4, 1)).thenReturn(room());
        when(mapper.lockActiveCourse(1, 1)).thenReturn(1L);
        when(mapper.findTeacherConflict(
                        1,
                        null,
                        2,
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31)))
                .thenReturn(null);
        when(academicMapper.findRoomOfferingConflict(
                        1,
                        4,
                        null,
                        2,
                        LocalTime.of(16, 30),
                        LocalTime.of(17, 30),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31)))
                .thenReturn(99L);

        assertCode(
                "ROOM_SCHEDULE_CONFLICT",
                () -> service.createOffering(linkedRequest("PUBLISHED")));
    }

    private CourseController.OfferingRequest request(String status) {
        return new CourseController.OfferingRequest(
                1L,
                1,
                1,
                "O-TEST",
                "2026-2027-1",
                2,
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 31, 18, 0),
                20,
                "美术教室",
                status,
                null,
                null,
                null);
    }

    private CourseController.OfferingRequest linkedRequest(String status) {
        return new CourseController.OfferingRequest(
                1L,
                1,
                1,
                "O-TEST",
                "2026-2027-1",
                2,
                LocalTime.of(16, 30),
                LocalTime.of(17, 30),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 31, 18, 0),
                20,
                "旧教室文本",
                status,
                2L,
                3L,
                4L);
    }

    private AcademicTerm term() {
        AcademicTerm term = new AcademicTerm();
        term.setId(2);
        term.setStartDate(LocalDate.of(2026, 9, 1));
        term.setEndDate(LocalDate.of(2027, 1, 31));
        term.setStatus("ACTIVE");
        return term;
    }

    private ServicePlan plan(String status) {
        ServicePlan plan = new ServicePlan();
        plan.setId(3);
        plan.setSchoolId(1);
        plan.setTermId(2);
        plan.setStatus(status);
        return plan;
    }

    private RoomResource room() {
        RoomResource room = new RoomResource();
        room.setId(4);
        room.setSchoolId(1);
        room.setRoomName("创客教室");
        room.setCapacity(30);
        room.setStatus("ACTIVE");
        return room;
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }
}
