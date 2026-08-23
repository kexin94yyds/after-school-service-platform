package com.afterschool.platform.teaching;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.auth.UserAccount;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeachingServiceTest {

    private TeachingMapper mapper;
    private CurrentUser currentUser;
    private TeachingService service;

    @BeforeEach
    void setUp() {
        mapper = mock(TeachingMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new TeachingService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-09-01T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void legacyOfferingGeneratesEachWeeklySessionWithoutCalendarLookup() {
        when(mapper.lockOfferingSchedule(10)).thenReturn(schedule(1, 20));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));
        when(mapper.listSessions(10)).thenReturn(List.of());

        service.generateSessions(10);

        verify(mapper, times(5)).insertSession(
                eq(1L),
                eq(10L),
                any(LocalDate.class),
                eq(LocalTime.of(16, 30)),
                eq(LocalTime.of(17, 30)),
                eq("美术教室"));
        verify(mapper).insertSession(
                eq(1L), eq(10L), eq(LocalDate.of(2026, 9, 29)),
                any(LocalTime.class), any(LocalTime.class), eq("美术教室"));
        verify(mapper, never()).listClosedCalendarDates(
                anyLong(), anyLong(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void standardTermOfferingSkipsHolidayAndSuspendedCalendarDates() {
        OfferingSchedule offering = schedule(1, 20);
        offering.setTermId(7L);
        when(mapper.lockOfferingSchedule(10)).thenReturn(offering);
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));
        when(mapper.listClosedCalendarDates(
                        1L,
                        7L,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(
                        LocalDate.of(2026, 9, 15),
                        LocalDate.of(2026, 9, 29)));
        when(mapper.listSessions(10)).thenReturn(List.of());

        service.generateSessions(10);

        verify(mapper).listClosedCalendarDates(
                1L,
                7L,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30));
        verify(mapper, times(3)).insertSession(
                eq(1L),
                eq(10L),
                any(LocalDate.class),
                eq(LocalTime.of(16, 30)),
                eq(LocalTime.of(17, 30)),
                eq("美术教室"));
        verify(mapper, never()).insertSession(
                eq(1L),
                eq(10L),
                eq(LocalDate.of(2026, 9, 15)),
                any(LocalTime.class),
                any(LocalTime.class),
                anyString());
        verify(mapper, never()).insertSession(
                eq(1L),
                eq(10L),
                eq(LocalDate.of(2026, 9, 29)),
                any(LocalTime.class),
                any(LocalTime.class),
                anyString());
    }

    @Test
    void generationSkipsEveryAppliedOriginalDateAcrossConsecutiveReschedules() {
        when(mapper.lockOfferingSchedule(10)).thenReturn(schedule(1, 20));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));
        when(mapper.listAppliedRescheduleOriginalDates(10)).thenReturn(List.of(
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 15)));
        when(mapper.listSessions(10)).thenReturn(List.of());

        service.generateSessions(10);

        verify(mapper).listAppliedRescheduleOriginalDates(10);
        verify(mapper, times(3)).insertSession(
                eq(1L),
                eq(10L),
                any(LocalDate.class),
                eq(LocalTime.of(16, 30)),
                eq(LocalTime.of(17, 30)),
                eq("美术教室"));
        verify(mapper, never()).insertSession(
                eq(1L),
                eq(10L),
                eq(LocalDate.of(2026, 9, 8)),
                any(LocalTime.class),
                any(LocalTime.class),
                anyString());
        verify(mapper, never()).insertSession(
                eq(1L),
                eq(10L),
                eq(LocalDate.of(2026, 9, 15)),
                any(LocalTime.class),
                any(LocalTime.class),
                anyString());
    }

    @Test
    void teacherCannotGenerateAnotherTeachersSessions() {
        when(mapper.lockOfferingSchedule(10)).thenReturn(schedule(1, 20));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 21L));

        assertThatThrownBy(() -> service.generateSessions(10))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void rejectsAttendanceBeforeSessionStart() {
        service = new TeachingService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-09-01T08:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
        when(mapper.lockLessonOwner(30)).thenReturn(lesson(
                30, 1, 10, LocalDate.of(2026, 9, 2), LocalTime.of(16, 30), "SCHEDULED"));
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "PUBLISHED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));

        TeachingController.AttendanceBatch batch = new TeachingController.AttendanceBatch(
                List.of(new TeachingController.AttendanceRecord(1, "PRESENT", null)));

        assertCode("ATTENDANCE_NOT_STARTED", () -> service.saveAttendance(30, batch));
    }

    @Test
    void rejectsPartialAttendanceRoster() {
        service = new TeachingService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-09-01T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
        when(mapper.lockLessonOwner(30)).thenReturn(lesson(
                30, 1, 10, LocalDate.of(2026, 9, 1), LocalTime.of(16, 30), "SCHEDULED"));
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "PUBLISHED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));
        when(mapper.listAttendance(30)).thenReturn(List.of(
                Map.of("studentId", 1L),
                Map.of("studentId", 2L)));

        TeachingController.AttendanceBatch batch = new TeachingController.AttendanceBatch(
                List.of(new TeachingController.AttendanceRecord(1, "PRESENT", null)));

        assertCode("INCOMPLETE_ATTENDANCE", () -> service.saveAttendance(30, batch));
    }

    @Test
    void fullAttendanceCompletesSession() {
        service = new TeachingService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-09-01T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
        LessonOwner lesson = lesson(
                30, 1, 10, LocalDate.of(2026, 9, 1), LocalTime.of(16, 30), "SCHEDULED");
        when(mapper.lockLessonOwner(30)).thenReturn(lesson);
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "PUBLISHED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));
        List<Map<String, Object>> roster = List.of(
                Map.of("studentId", 1L),
                Map.of("studentId", 2L));
        when(mapper.listAttendance(30)).thenReturn(roster);
        when(mapper.countAttendanceEligible(10, 30, 1)).thenReturn(1);
        when(mapper.countAttendanceEligible(10, 30, 2)).thenReturn(1);

        TeachingController.AttendanceBatch batch = new TeachingController.AttendanceBatch(List.of(
                new TeachingController.AttendanceRecord(1, "PRESENT", null),
                new TeachingController.AttendanceRecord(2, "ABSENT", "病假")));
        service.saveAttendance(30, batch);

        verify(mapper).upsertAttendance(1, 10, 30, 1, "PRESENT", 1, null);
        verify(mapper).upsertAttendance(1, 10, 30, 2, "ABSENT", 1, "病假");
        verify(mapper).updateSession(30, "COMPLETED", null);
    }

    @Test
    void updatesTeachingNotesWithoutChangingSessionStatus() {
        when(mapper.lockLessonOwner(30)).thenReturn(lesson(
                30, 1, 10, LocalDate.of(2026, 9, 1), LocalTime.of(16, 30), "SCHEDULED"));
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "PUBLISHED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));

        when(mapper.updateSession(30, "SCHEDULED", "课堂记录")).thenReturn(1);
        when(mapper.listSessions(10)).thenReturn(List.of(Map.of("id", 30L)));

        service.updateSession(30, new TeachingController.SessionRequest("课堂记录"));

        verify(mapper).updateSession(30, "SCHEDULED", "课堂记录");
    }

    @Test
    void canceledOfferingCannotCreateOrOverwriteAttendance() {
        when(mapper.lockLessonOwner(30)).thenReturn(lesson(
                30, 1, 10, LocalDate.of(2026, 9, 1), LocalTime.of(16, 30), "COMPLETED"));
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "CANCELED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));

        TeachingController.AttendanceBatch batch = new TeachingController.AttendanceBatch(
                List.of(new TeachingController.AttendanceRecord(1, "ABSENT", "改写历史记录")));

        assertCode("OFFERING_CANCELED", () -> service.saveAttendance(30, batch));
        verify(mapper, never()).upsertAttendance(
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void completedSessionAttendanceCannotBeOverwritten() {
        when(mapper.findLessonOfferingId(30)).thenReturn(10L);
        when(mapper.lockOfferingAccess(10)).thenReturn(offeringAccess(20, "PUBLISHED"));
        when(mapper.lockLessonOwner(30)).thenReturn(lesson(
                30, 1, 10, LocalDate.of(2026, 9, 1), LocalTime.of(16, 30), "COMPLETED"));
        when(currentUser.principal()).thenReturn(principal("TEACHER", 1L, 20L));

        TeachingController.AttendanceBatch batch = new TeachingController.AttendanceBatch(
                List.of(new TeachingController.AttendanceRecord(1, "ABSENT", "改写历史记录")));

        assertCode("SESSION_COMPLETED", () -> service.saveAttendance(30, batch));
        verify(mapper, never()).upsertAttendance(
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    private OfferingSchedule schedule(long schoolId, long teacherId) {
        OfferingSchedule value = new OfferingSchedule();
        value.setId(10);
        value.setSchoolId(schoolId);
        value.setTeacherId(teacherId);
        value.setWeekDay(2);
        value.setStartDate(LocalDate.of(2026, 9, 1));
        value.setEndDate(LocalDate.of(2026, 9, 30));
        value.setStartTime(LocalTime.of(16, 30));
        value.setEndTime(LocalTime.of(17, 30));
        value.setClassroom("美术教室");
        value.setStatus("PUBLISHED");
        return value;
    }

    private LessonOwner lesson(
            long id,
            long schoolId,
            long offeringId,
            LocalDate sessionDate,
            LocalTime startTime,
            String status) {
        LessonOwner value = new LessonOwner();
        value.setId(id);
        value.setSchoolId(schoolId);
        value.setOfferingId(offeringId);
        value.setSessionDate(sessionDate);
        value.setStartTime(startTime);
        value.setStatus(status);
        return value;
    }

    private LessonOwner offeringAccess(long teacherId, String status) {
        LessonOwner value = new LessonOwner();
        value.setTeacherId(teacherId);
        value.setOfferingStatus(status);
        return value;
    }

    private PlatformPrincipal principal(String role, Long schoolId, Long teacherId) {
        UserAccount account = new UserAccount();
        account.setId(1);
        account.setUsername("test");
        account.setPasswordHash("{noop}test");
        account.setDisplayName("测试账号");
        account.setEnabled(true);
        account.setRoleCode(role);
        account.setSchoolId(schoolId);
        account.setTeacherId(teacherId);
        return new PlatformPrincipal(account);
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }
}
