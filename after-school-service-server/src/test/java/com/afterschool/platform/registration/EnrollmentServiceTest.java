package com.afterschool.platform.registration;

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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class EnrollmentServiceTest {

    private EnrollmentMapper mapper;
    private CurrentUser currentUser;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EnrollmentMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new EnrollmentService(
                mapper,
                currentUser,
                new EnrollmentRuleEngine(),
                Clock.fixed(
                        Instant.parse("2026-09-10T08:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void schoolAdministratorCanCorrectEnrollmentAfterCourseStarts() {
        PlatformPrincipal principal = mock(PlatformPrincipal.class);
        when(principal.id()).thenReturn(11L);
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        when(principal.schoolId()).thenReturn(1L);
        when(currentUser.principal()).thenReturn(principal);

        EnrollmentRecord record = new EnrollmentRecord();
        record.setId(30);
        record.setOfferingId(20);
        record.setStudentId(10);
        record.setStatus("ENROLLED");
        when(mapper.findSchoolEnrollment(30, 1)).thenReturn(record);
        when(mapper.lockSchoolStudent(10, 1)).thenReturn(10L);
        EnrollmentOffering offering = new EnrollmentOffering();
        offering.setSchoolId(1);
        when(mapper.lockOffering(20)).thenReturn(offering);
        EnrollmentRecord lockedRecord = new EnrollmentRecord();
        lockedRecord.setId(30);
        lockedRecord.setOfferingId(20);
        lockedRecord.setStudentId(10);
        lockedRecord.setStatus("ENROLLED");
        when(mapper.lockSchoolEnrollment(30, 1)).thenReturn(lockedRecord);
        when(mapper.cancelEnrollment(30, null, 1L, 11)).thenReturn(1);
        when(mapper.decrementCapacity(20)).thenReturn(1);
        when(mapper.findEnrollmentView(20, 10)).thenReturn(enrollmentView());
        when(mapper.insertEnrollmentAction(
                        1, 30, 20, 10, 8, "CANCEL", null, 11, "SCHOOL_ADMIN"))
                .thenReturn(1);

        service.cancel(30);

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockOffering(20);
        order.verify(mapper).lockSchoolStudent(10, 1);
        order.verify(mapper).lockSchoolEnrollment(30, 1);
        order.verify(mapper).cancelEnrollment(30, null, 1L, 11);
        order.verify(mapper).decrementCapacity(20);
        order.verify(mapper)
                .withdrawActiveLeavesForEnrollment(1, 20, 10, 11);
    }

    @Test
    void guardianCannotCancelEnrollmentAfterOwnershipChangesWhileWaitingForLocks() {
        PlatformPrincipal principal = mock(PlatformPrincipal.class);
        when(principal.id()).thenReturn(21L);
        when(principal.roleCode()).thenReturn("GUARDIAN");
        when(principal.guardianId()).thenReturn(8L);
        when(currentUser.principal()).thenReturn(principal);

        EnrollmentRecord initialRecord = new EnrollmentRecord();
        initialRecord.setId(30);
        initialRecord.setOfferingId(20);
        initialRecord.setStudentId(10);
        initialRecord.setStatus("ENROLLED");
        when(mapper.findScopedEnrollment(30, 8)).thenReturn(initialRecord);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(new EnrollmentStudent());
        when(mapper.lockOffering(20)).thenReturn(new EnrollmentOffering());
        // A different guardian reactivated the row before this transaction acquired
        // the shared student/offering locks, so the scoped locking read no longer matches.
        when(mapper.lockScopedEnrollment(30, 8)).thenReturn(null);

        assertThatThrownBy(() -> service.cancel(30))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("ENROLLMENT_CHANGED");

        verify(mapper, never()).cancelEnrollment(30, 8L, null, 21);
        verify(mapper, never()).decrementCapacity(20);
    }

    @Test
    void rejectsEnrollmentAfterAnActualFirstSessionWasMovedEarlierThanTheTemplate() {
        PlatformPrincipal principal = guardianPrincipal();
        when(currentUser.principal()).thenReturn(principal);
        EnrollmentOffering offering = offering();
        offering.setStartDate(LocalDate.of(2026, 9, 15));
        when(mapper.lockOffering(20)).thenReturn(offering);
        when(mapper.findOffering(20)).thenReturn(offering);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(student());
        when(mapper.findFirstValidSessionStart(20))
                .thenReturn(LocalDateTime.of(2026, 9, 10, 15, 0));

        assertCode(
                "ENROLLMENT_CLOSED_AFTER_START",
                () -> service.enroll(new EnrollmentController.EnrollmentRequest(10, 20)));

        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockOffering(20);
        locks.verify(mapper).findOffering(20);
        locks.verify(mapper).lockGuardianStudent(10, 8);
        verify(mapper, never()).incrementCapacity(20);
    }

    @Test
    void usesTemplateFirstSessionOnlyWhenNoEffectiveSessionExists() {
        PlatformPrincipal principal = guardianPrincipal();
        when(currentUser.principal()).thenReturn(principal);
        EnrollmentOffering offering = offering();
        offering.setStartDate(LocalDate.of(2026, 9, 15));
        when(mapper.lockOffering(20)).thenReturn(offering);
        when(mapper.findOffering(20)).thenReturn(offering);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(student());
        when(mapper.findFirstValidSessionStart(20)).thenReturn(null);
        when(mapper.incrementCapacity(20)).thenReturn(1);
        when(mapper.findEnrollmentView(20, 10)).thenReturn(enrollmentView());
        when(mapper.insertEnrollmentAction(
                        1, 30, 20, 10, 8, "ENROLL", null, 21, "GUARDIAN"))
                .thenReturn(1);

        service.enroll(new EnrollmentController.EnrollmentRequest(10, 20));

        verify(mapper).findFirstValidSessionStart(20);
        verify(mapper).insertEnrollment(1, 20, 10, 8);
    }

    @Test
    void rejectsEnrollmentWhenActualScheduleConflictQueryFindsAnotherCourse() {
        PlatformPrincipal principal = guardianPrincipal();
        when(currentUser.principal()).thenReturn(principal);
        EnrollmentOffering offering = offering();
        offering.setStartDate(LocalDate.of(2026, 9, 15));
        when(mapper.lockOffering(20)).thenReturn(offering);
        when(mapper.findOffering(20)).thenReturn(offering);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(student());
        when(mapper.findFirstValidSessionStart(20))
                .thenReturn(LocalDateTime.of(2026, 9, 15, 16, 30));
        when(mapper.countScheduleConflicts(10, 20)).thenReturn(1);

        assertCode(
                "STUDENT_SCHEDULE_CONFLICT",
                () -> service.enroll(new EnrollmentController.EnrollmentRequest(10, 20)));

        verify(mapper, never()).incrementCapacity(20);
    }

    @Test
    void readsClosedParentStatusAfterLockingOfferingAndRejectsEnrollment() {
        PlatformPrincipal principal = guardianPrincipal();
        when(currentUser.principal()).thenReturn(principal);
        EnrollmentOffering lockedOffering = offering();
        EnrollmentOffering offeringWithParentState = offering();
        offeringWithParentState.setTermStatus("CLOSED");
        when(mapper.lockOffering(20)).thenReturn(lockedOffering);
        when(mapper.findOffering(20)).thenReturn(offeringWithParentState);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(student());

        assertCode(
                "TERM_CLOSED",
                () -> service.enroll(new EnrollmentController.EnrollmentRequest(10, 20)));

        InOrder operations = inOrder(mapper);
        operations.verify(mapper).lockOffering(20);
        operations.verify(mapper).findOffering(20);
        operations.verify(mapper).lockGuardianStudent(10, 8);
        verify(mapper, never()).incrementCapacity(20);
    }

    @Test
    void switchesEnrollmentAtomicallyAndRecordsBothSides() {
        PlatformPrincipal principal = guardianPrincipal();
        when(currentUser.principal()).thenReturn(principal);
        EnrollmentRecord oldRecord = new EnrollmentRecord();
        oldRecord.setId(30);
        oldRecord.setOfferingId(20);
        oldRecord.setStudentId(10);
        oldRecord.setStatus("ENROLLED");
        when(mapper.findScopedEnrollment(30, 8)).thenReturn(oldRecord);
        when(mapper.lockScopedEnrollment(30, 8)).thenReturn(oldRecord);

        EnrollmentOffering oldOffering = offering();
        oldOffering.setStartDate(LocalDate.of(2026, 9, 15));
        EnrollmentOffering newOffering = offering();
        newOffering.setId(21);
        newOffering.setStartDate(LocalDate.of(2026, 9, 16));
        when(mapper.lockOffering(20)).thenReturn(oldOffering);
        when(mapper.lockOffering(21)).thenReturn(newOffering);
        when(mapper.findOffering(20)).thenReturn(oldOffering);
        when(mapper.findOffering(21)).thenReturn(newOffering);
        when(mapper.lockGuardianStudent(10, 8)).thenReturn(student());
        when(mapper.cancelEnrollment(30, 8L, null, 21)).thenReturn(1);
        when(mapper.decrementCapacity(20)).thenReturn(1);
        when(mapper.incrementCapacity(21)).thenReturn(1);
        Map<String, Object> oldView = enrollmentView();
        Map<String, Object> newView = Map.of(
                "id", 31L,
                "schoolId", 1L,
                "offeringId", 21L,
                "studentId", 10L,
                "guardianId", 8L);
        when(mapper.findEnrollmentView(20, 10)).thenReturn(oldView);
        when(mapper.findEnrollmentView(21, 10)).thenReturn(newView);
        when(mapper.insertEnrollmentAction(
                        1, 30, 20, 10, 8, "SWITCH_OUT", 31L, 21, "GUARDIAN"))
                .thenReturn(1);
        when(mapper.insertEnrollmentAction(
                        1, 31, 21, 10, 8, "SWITCH_IN", 30L, 21, "GUARDIAN"))
                .thenReturn(1);

        Map<String, Object> result = service.switchEnrollment(30, 21);

        org.assertj.core.api.Assertions.assertThat(result).containsEntry("id", 31L);
        verify(mapper).cancelEnrollment(30, 8L, null, 21);
        verify(mapper).insertEnrollment(1, 21, 10, 8);
        verify(mapper).insertEnrollmentAction(
                1, 30, 20, 10, 8, "SWITCH_OUT", 31L, 21, "GUARDIAN");
        verify(mapper).insertEnrollmentAction(
                1, 31, 21, 10, 8, "SWITCH_IN", 30L, 21, "GUARDIAN");
    }

    private PlatformPrincipal guardianPrincipal() {
        PlatformPrincipal principal = mock(PlatformPrincipal.class);
        when(principal.id()).thenReturn(21L);
        when(principal.roleCode()).thenReturn("GUARDIAN");
        when(principal.guardianId()).thenReturn(8L);
        return principal;
    }

    private EnrollmentStudent student() {
        EnrollmentStudent student = new EnrollmentStudent();
        student.setId(10);
        student.setSchoolId(1);
        student.setGrade(3);
        student.setStatus("ACTIVE");
        return student;
    }

    private EnrollmentOffering offering() {
        EnrollmentOffering offering = new EnrollmentOffering();
        offering.setId(20);
        offering.setSchoolId(1);
        offering.setTargetGradeMin(1);
        offering.setTargetGradeMax(6);
        offering.setWeekDay(2);
        offering.setStartTime(LocalTime.of(16, 30));
        offering.setEndTime(LocalTime.of(17, 30));
        offering.setStartDate(LocalDate.of(2026, 9, 1));
        offering.setEndDate(LocalDate.of(2027, 1, 31));
        offering.setEnrollmentStart(LocalDateTime.of(2026, 9, 1, 0, 0));
        offering.setEnrollmentEnd(LocalDateTime.of(2026, 12, 31, 23, 59));
        offering.setCapacity(20);
        offering.setEnrolledCount(0);
        offering.setStatus("PUBLISHED");
        offering.setCourseStatus("ACTIVE");
        offering.setTermStatus("ACTIVE");
        offering.setPlanStatus("FILED");
        return offering;
    }

    private Map<String, Object> enrollmentView() {
        return Map.of(
                "id", 30L,
                "schoolId", 1L,
                "offeringId", 20L,
                "studentId", 10L,
                "guardianId", 8L);
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }
}
