package com.afterschool.platform.leavecorrection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.auth.UserAccount;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class LeaveCorrectionServiceTest {

    private LeaveCorrectionMapper mapper;
    private CurrentUser currentUser;
    private LeaveCorrectionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(LeaveCorrectionMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new LeaveCorrectionService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-09-01T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")));
        when(mapper.lockGuardianStudentBinding(anyLong(), anyLong(), anyLong()))
                .thenReturn(40L);
    }

    @Test
    void studentCanSubmitForOwnEffectivelyEnrolledFutureSession() {
        PlatformPrincipal student = principal(3, "STUDENT", 1L, null, null);
        when(currentUser.principal()).thenReturn(student);
        stubSessionLocks(futureSession());
        when(mapper.lockGuardianEligibleEnrollment(
                        1,
                        10,
                        40,
                        LocalDateTime.of(2026, 9, 2, 16, 30)))
                .thenReturn(90L);
        when(mapper.findLeaveView(anyLong())).thenReturn(Map.of("status", "PENDING"));
        when(mapper.insertLeave(any(), eq("发烧就医"), eq(3L))).thenReturn(1);

        Map<String, Object> result = service.submitLeave(
                new LeaveCorrectionController.LeaveSubmission(30, 40, " 发烧就医 "));

        assertThat(result.get("status")).isEqualTo("PENDING");
        ArgumentCaptor<LeaveRequestRecord> record =
                ArgumentCaptor.forClass(LeaveRequestRecord.class);
        verify(mapper).insertLeave(record.capture(), eq("发烧就医"), eq(3L));
        assertThat(record.getValue().getSchoolId()).isEqualTo(1);
        assertThat(record.getValue().getOfferingId()).isEqualTo(10);
        assertThat(record.getValue().getGuardianId()).isNull();
        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockOfferingContext(10);
        locks.verify(mapper).lockSessionContext(30);
        locks.verify(mapper)
                .lockGuardianEligibleEnrollment(
                        1,
                        10,
                        40,
                        LocalDateTime.of(2026, 9, 2, 16, 30));
        locks.verify(mapper).insertLeave(any(), eq("发烧就医"), eq(3L));
    }

    @Test
    void guardianLeaveSessionListIsScopedByBindingAndEffectiveEnrollment() {
        when(currentUser.principal())
                .thenReturn(principal(3, "GUARDIAN", 1L, null, 30L));
        when(mapper.listGuardianLeaveSessions(
                        40,
                        30,
                        LocalDateTime.of(2026, 9, 1, 18, 0)))
                .thenReturn(List.of(Map.of("id", 30L)));

        List<Map<String, Object>> result = service.guardianLeaveSessions(40);

        assertThat(result).extracting(item -> item.get("id")).containsExactly(30L);
    }

    @Test
    void studentCannotSubmitForIneffectiveEnrollment() {
        when(currentUser.principal())
                .thenReturn(principal(3, "STUDENT", 1L, null, null));
        stubSessionLocks(futureSession());
        when(mapper.lockGuardianEligibleEnrollment(
                        1,
                        10,
                        40,
                        LocalDateTime.of(2026, 9, 2, 16, 30)))
                .thenReturn(null);

        assertCode(
                "FORBIDDEN",
                () -> service.submitLeave(
                        new LeaveCorrectionController.LeaveSubmission(
                                30, 40, "不是绑定学生")));

        verify(mapper, never()).insertLeave(any(), any(), anyLong());
    }

    @Test
    void canceledEnrollmentCannotCreateLeaveAfterInitialSessionSnapshot() {
        when(currentUser.principal())
                .thenReturn(principal(3, "STUDENT", 1L, null, null));
        stubSessionLocks(futureSession());
        when(mapper.lockGuardianEligibleEnrollment(
                        1,
                        10,
                        40,
                        LocalDateTime.of(2026, 9, 2, 16, 30)))
                .thenReturn(null);

        assertCode(
                "FORBIDDEN",
                () -> service.submitLeave(
                        new LeaveCorrectionController.LeaveSubmission(
                                30, 40, "取消报名后不应成功")));

        InOrder currentRead = inOrder(mapper);
        currentRead.verify(mapper).findSessionContext(30);
        currentRead.verify(mapper).lockOfferingContext(10);
        currentRead.verify(mapper).lockSessionContext(30);
        currentRead.verify(mapper)
                .lockGuardianEligibleEnrollment(
                        1,
                        10,
                        40,
                        LocalDateTime.of(2026, 9, 2, 16, 30));
        verify(mapper, never()).insertLeave(any(), any(), anyLong());
    }

    @Test
    void leaveCannotBeSubmittedAtOrAfterSessionStart() {
        SessionWorkflowContext started = futureSession();
        started.setSessionDate(LocalDate.of(2026, 9, 1));
        started.setStartTime(LocalTime.of(18, 0));
        when(currentUser.principal())
                .thenReturn(principal(3, "STUDENT", 1L, null, null));
        stubSessionLocks(started);

        assertCode(
                "LEAVE_DEADLINE_PASSED",
                () -> service.submitLeave(
                        new LeaveCorrectionController.LeaveSubmission(
                                30, 40, "迟到后补请假")));
    }

    @Test
    void teacherMayOnlyReviewOwnOfferingLeave() {
        LeaveRequestRecord initial = leaveRecord("PENDING");
        when(mapper.findLeaveRecord(50)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockLeaveRecord(50)).thenReturn(leaveRecord("PENDING"));
        when(currentUser.principal())
                .thenReturn(principal(1, "TEACHER", 1L, 99L, null));

        assertCode(
                "FORBIDDEN",
                () -> service.reviewLeave(
                        50,
                        new LeaveCorrectionController.LeaveReview(
                                "APPROVED", "同意")));

        verify(mapper, never())
                .reviewLeave(anyLong(), any(), any(), anyLong());
    }

    @Test
    void rejectingLeaveRequiresANonBlankReason() {
        assertCode(
                "LEAVE_REJECTION_REASON_REQUIRED",
                () -> service.reviewLeave(
                        50,
                        new LeaveCorrectionController.LeaveReview(
                                "REJECTED", "  ")));

        verify(mapper, never()).findLeaveRecord(50);
    }

    @Test
    void schoolAdminCanReviewPendingLeaveInOwnSchool() {
        LeaveRequestRecord initial = leaveRecord("PENDING");
        when(mapper.findLeaveRecord(50)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockLeaveRecord(50)).thenReturn(leaveRecord("PENDING"));
        when(currentUser.principal())
                .thenReturn(principal(2, "SCHOOL_ADMIN", 1L, null, null));
        when(mapper.lockReviewableLeaveEnrollment(1, 10, 40))
                .thenReturn(90L);
        when(mapper.reviewLeave(50, "APPROVED", "材料已核验", 2)).thenReturn(1);
        when(mapper.findLeaveView(50)).thenReturn(Map.of("status", "APPROVED"));

        Map<String, Object> result = service.reviewLeave(
                50,
                new LeaveCorrectionController.LeaveReview(
                        "APPROVED", " 材料已核验 "));

        assertThat(result.get("status")).isEqualTo("APPROVED");
        verify(mapper).reviewLeave(50, "APPROVED", "材料已核验", 2);
        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockOfferingContext(10);
        locks.verify(mapper).lockSessionContext(30);
        locks.verify(mapper)
                .lockReviewableLeaveEnrollment(1, 10, 40);
        locks.verify(mapper).lockLeaveRecord(50);
        locks.verify(mapper)
                .reviewLeave(50, "APPROVED", "材料已核验", 2);
    }

    @Test
    void teacherMayApproveBeforeAttendanceIsFinalizedEvenAfterClassStarted() {
        LeaveRequestRecord initial = leaveRecord("PENDING");
        initial.setSessionDate(LocalDate.of(2026, 9, 1));
        initial.setStartTime(LocalTime.of(17, 0));
        LeaveRequestRecord locked = leaveRecord("PENDING");
        when(mapper.findLeaveRecord(50)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockLeaveRecord(50)).thenReturn(locked);
        when(currentUser.principal())
                .thenReturn(principal(1, "TEACHER", 1L, 20L, null));
        when(mapper.lockReviewableLeaveEnrollment(1, 10, 40))
                .thenReturn(90L);
        when(mapper.reviewLeave(50, "APPROVED", null, 1)).thenReturn(1);
        when(mapper.findLeaveView(50)).thenReturn(Map.of("status", "APPROVED"));

        Map<String, Object> result = service.reviewLeave(
                50,
                new LeaveCorrectionController.LeaveReview(
                        "APPROVED", null));

        assertThat(result.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void leaveCannotBeApprovedAfterEnrollmentOrBindingBecomesInactive() {
        LeaveRequestRecord initial = leaveRecord("PENDING");
        when(mapper.findLeaveRecord(50)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockLeaveRecord(50)).thenReturn(leaveRecord("PENDING"));
        when(currentUser.principal())
                .thenReturn(principal(2, "SCHOOL_ADMIN", 1L, null, null));
        when(mapper.lockReviewableLeaveEnrollment(1, 10, 40))
                .thenReturn(null);

        assertCode(
                "LEAVE_ENROLLMENT_INACTIVE",
                () -> service.reviewLeave(
                        50,
                        new LeaveCorrectionController.LeaveReview(
                                "APPROVED", null)));

        verify(mapper, never())
                .reviewLeave(anyLong(), any(), any(), anyLong());
    }

    @Test
    void completedAttendanceCorrectionIsAppliedAtomicallyWithRevisionFirst() {
        AttendanceCorrectionRecord initial = correction("PENDING");
        when(mapper.findCorrectionRecord(60)).thenReturn(initial);
        stubSessionLocks(initial);
        AttendanceCorrectionRecord attendance = attendanceTarget();
        when(mapper.lockAttendanceTarget(70)).thenReturn(attendance);
        when(mapper.lockCorrectionRecord(60)).thenReturn(correction("PENDING"));
        when(currentUser.principal())
                .thenReturn(principal(2, "SCHOOL_ADMIN", 1L, null, null));
        when(mapper.insertAttendanceRevision(any(), any(), eq(2L))).thenReturn(1);
        when(mapper.updateAttendanceFromCorrection(70, "LEAVE", "已核验病假", 2))
                .thenReturn(1);
        when(mapper.markCorrectionApplied(60, "批准", 2)).thenReturn(1);
        when(mapper.findCorrectionView(60)).thenReturn(Map.of("status", "APPLIED"));

        Map<String, Object> result = service.reviewCorrection(
                60,
                new LeaveCorrectionController.CorrectionReview(
                        "APPROVED", "批准"));

        assertThat(result.get("status")).isEqualTo("APPLIED");
        InOrder writes = inOrder(mapper);
        writes.verify(mapper)
                .insertAttendanceRevision(any(), eq(attendance), eq(2L));
        writes.verify(mapper)
                .updateAttendanceFromCorrection(70, "LEAVE", "已核验病假", 2);
        writes.verify(mapper).markCorrectionApplied(60, "批准", 2);
    }

    @Test
    void correctionMayOnlyBeRequestedForCompletedAttendance() {
        AttendanceCorrectionRecord target = attendanceTarget();
        target.setSessionStatus("SCHEDULED");
        when(mapper.findCorrectionTarget(30, 40)).thenReturn(target);
        stubSessionLocks(target);
        when(mapper.lockAttendanceTarget(70)).thenReturn(target);
        when(currentUser.principal())
                .thenReturn(principal(1, "TEACHER", 1L, 20L, null));

        assertCode(
                "SESSION_NOT_COMPLETED",
                () -> service.requestCorrection(
                        new LeaveCorrectionController.CorrectionSubmission(
                                30, 40, "LEAVE", "病假", "补录请假")));

        verify(mapper, never()).insertCorrection(any(), anyLong());
    }

    @Test
    void teacherCannotCancelAnotherTeachersCorrection() {
        AttendanceCorrectionRecord initial = correction("PENDING");
        initial.setRequestedBy(99);
        when(mapper.findCorrectionRecord(60)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockAttendanceTarget(70)).thenReturn(attendanceTarget());
        AttendanceCorrectionRecord locked = correction("PENDING");
        locked.setRequestedBy(99);
        when(mapper.lockCorrectionRecord(60)).thenReturn(locked);
        when(currentUser.principal())
                .thenReturn(principal(1, "TEACHER", 1L, 20L, null));

        assertCode("FORBIDDEN", () -> service.cancelCorrection(60));

        verify(mapper, never()).cancelCorrection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void schoolAdminCannotApplyCorrectionFromAnotherSchool() {
        AttendanceCorrectionRecord initial = correction("PENDING");
        when(mapper.findCorrectionRecord(60)).thenReturn(initial);
        stubSessionLocks(initial);
        when(mapper.lockAttendanceTarget(70)).thenReturn(attendanceTarget());
        when(mapper.lockCorrectionRecord(60)).thenReturn(correction("PENDING"));
        when(currentUser.principal())
                .thenReturn(principal(2, "SCHOOL_ADMIN", 2L, null, null));

        assertCode(
                "FORBIDDEN",
                () -> service.reviewCorrection(
                        60,
                        new LeaveCorrectionController.CorrectionReview(
                                "APPROVED", null)));

        verify(mapper, never()).insertAttendanceRevision(any(), any(), anyLong());
        verify(mapper, never())
                .updateAttendanceFromCorrection(
                        anyLong(), any(), any(), anyLong());
    }

    @Test
    void rejectingCorrectionRequiresANonBlankReason() {
        assertCode(
                "CORRECTION_REJECTION_REASON_REQUIRED",
                () -> service.reviewCorrection(
                        60,
                        new LeaveCorrectionController.CorrectionReview(
                                "REJECTED", null)));

        verify(mapper, never()).findCorrectionRecord(60);
    }

    @Test
    void guardianMayReadOnlyBoundStudentsRevisionHistory() {
        AttendanceCorrectionRecord attendance = attendanceTarget();
        when(mapper.findAttendanceTargetById(70)).thenReturn(attendance);
        when(mapper.countGuardianAttendanceAccess(70, 30)).thenReturn(1);
        when(mapper.listAttendanceRevisions(70))
                .thenReturn(List.of(Map.of("newStatus", "LEAVE")));
        when(currentUser.principal())
                .thenReturn(principal(3, "GUARDIAN", 1L, null, 30L));

        List<Map<String, Object>> result = service.revisions(70);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("newStatus")).isEqualTo("LEAVE");
    }

    private void stubSessionLocks(SessionWorkflowContext context) {
        SessionWorkflowContext offering = new SessionWorkflowContext();
        offering.setSchoolId(context.getSchoolId());
        offering.setOfferingId(context.getOfferingId());
        offering.setTeacherId(context.getTeacherId());
        offering.setOfferingStatus(context.getOfferingStatus());
        SessionWorkflowContext session = new SessionWorkflowContext();
        session.setSchoolId(context.getSchoolId());
        session.setOfferingId(context.getOfferingId());
        session.setSessionId(context.getSessionId());
        session.setSessionDate(context.getSessionDate());
        session.setStartTime(context.getStartTime());
        session.setSessionStatus(context.getSessionStatus());
        when(mapper.lockOfferingContext(context.getOfferingId())).thenReturn(offering);
        when(mapper.lockSessionContext(context.getSessionId())).thenReturn(session);
        if (context.getClass() == SessionWorkflowContext.class) {
            when(mapper.findSessionContext(context.getSessionId())).thenReturn(context);
        }
    }

    private SessionWorkflowContext futureSession() {
        SessionWorkflowContext value = new SessionWorkflowContext();
        setContext(
                value,
                LocalDate.of(2026, 9, 2),
                LocalTime.of(16, 30),
                "SCHEDULED");
        return value;
    }

    private LeaveRequestRecord leaveRecord(String status) {
        LeaveRequestRecord value = new LeaveRequestRecord();
        value.setId(50);
        value.setStudentId(40);
        value.setGuardianId(null);
        value.setSubmittedBy(3);
        value.setStatus(status);
        setContext(
                value,
                LocalDate.of(2026, 9, 2),
                LocalTime.of(16, 30),
                "SCHEDULED");
        return value;
    }

    private AttendanceCorrectionRecord attendanceTarget() {
        AttendanceCorrectionRecord value = new AttendanceCorrectionRecord();
        value.setAttendanceId(70);
        value.setStudentId(40);
        value.setAttendanceStatus("ABSENT");
        value.setAttendanceRemark("未到");
        value.setAttendanceRecordedBy(1);
        value.setAttendanceRecordedAt(LocalDateTime.of(2026, 9, 1, 18, 0));
        setContext(
                value,
                LocalDate.of(2026, 9, 1),
                LocalTime.of(16, 30),
                "COMPLETED");
        return value;
    }

    private AttendanceCorrectionRecord correction(String status) {
        AttendanceCorrectionRecord value = attendanceTarget();
        value.setId(60);
        value.setRequestedBy(1);
        value.setRequestedStatus("LEAVE");
        value.setRequestedRemark("已核验病假");
        value.setReason("家长补交病假材料");
        value.setStatus(status);
        return value;
    }

    private void setContext(
            SessionWorkflowContext value,
            LocalDate date,
            LocalTime time,
            String sessionStatus) {
        value.setSchoolId(1);
        value.setOfferingId(10);
        value.setSessionId(30);
        value.setTeacherId(20);
        value.setSessionDate(date);
        value.setStartTime(time);
        value.setSessionStatus(sessionStatus);
        value.setOfferingStatus("PUBLISHED");
    }

    private PlatformPrincipal principal(
            long userId,
            String role,
            Long schoolId,
            Long teacherId,
            Long guardianId) {
        UserAccount account = new UserAccount();
        account.setId(userId);
        account.setUsername("test-" + userId);
        account.setPasswordHash("{noop}test");
        account.setDisplayName("测试账号");
        account.setEnabled(true);
        account.setRoleCode(role);
        account.setSchoolId(schoolId);
        account.setTeacherId(teacherId);
        account.setGuardianId(guardianId);
        if ("STUDENT".equals(role)) {
            account.setStudentId(40L);
        }
        return new PlatformPrincipal(account);
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }
}
