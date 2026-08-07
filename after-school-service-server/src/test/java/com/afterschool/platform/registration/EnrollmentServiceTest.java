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
import java.time.ZoneId;
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

        service.cancel(30);

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockSchoolStudent(10, 1);
        order.verify(mapper).lockOffering(20);
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
}
