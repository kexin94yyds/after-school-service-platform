package com.afterschool.platform.leavecorrection;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveCorrectionService {

    private final LeaveCorrectionMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public LeaveCorrectionService(
            LeaveCorrectionMapper mapper, CurrentUser currentUser, Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public List<Map<String, Object>> guardianLeaveSessions(long studentId) {
        PlatformPrincipal principal = requireGuardian();
        return mapper.listGuardianLeaveSessions(
                studentId, principal.guardianId(), LocalDateTime.now(clock));
    }

    @Transactional
    public Map<String, Object> submitLeave(
            LeaveCorrectionController.LeaveSubmission request) {
        PlatformPrincipal principal = requireGuardian();
        SessionWorkflowContext initial = mapper.findSessionContext(request.sessionId());
        if (initial == null) {
            throw ApiException.notFound("课次不存在");
        }
        requireSameSchool(principal, initial.getSchoolId());
        if (mapper.lockGuardianStudentBinding(
                        initial.getSchoolId(),
                        request.studentId(),
                        principal.guardianId())
                == null) {
            throw ApiException.forbidden("只能为已绑定且该课次有效报名的学生请假");
        }
        SessionWorkflowContext locked = lockSession(initial);
        requireSameSchool(principal, locked.getSchoolId());
        validateLeaveSubmissionWindow(locked);
        if (mapper.lockGuardianEligibleEnrollment(
                        locked.getSchoolId(),
                        locked.getOfferingId(),
                        request.studentId(),
                        LocalDateTime.of(
                                locked.getSessionDate(), locked.getStartTime()))
                == null) {
            throw ApiException.forbidden("只能为已绑定且该课次有效报名的学生请假");
        }
        if (mapper.countActiveLeave(locked.getSessionId(), request.studentId()) > 0) {
            throw ApiException.conflict(
                    "ACTIVE_LEAVE_EXISTS", "该学生在本课次已有待审核或已批准请假");
        }

        LeaveRequestRecord record = new LeaveRequestRecord();
        record.setSchoolId(locked.getSchoolId());
        record.setOfferingId(locked.getOfferingId());
        record.setSessionId(locked.getSessionId());
        record.setStudentId(request.studentId());
        record.setGuardianId(principal.guardianId());
        if (mapper.insertLeave(record, request.reason().strip(), principal.id()) != 1) {
            throw ApiException.conflict("LEAVE_SUBMIT_FAILED", "请假申请未能保存");
        }
        return requireLeaveView(record.getId());
    }

    @Transactional
    public Map<String, Object> withdrawLeave(long id) {
        PlatformPrincipal principal = requireGuardian();
        LeaveRequestRecord initial = mapper.findLeaveRecord(id);
        if (initial == null) {
            throw ApiException.notFound("请假申请不存在");
        }
        requireSameSchool(principal, initial.getSchoolId());
        if (initial.getGuardianId() != principal.guardianId()) {
            throw ApiException.forbidden("只能撤回本人提交的请假");
        }
        if (mapper.lockGuardianStudentBinding(
                        initial.getSchoolId(),
                        initial.getStudentId(),
                        initial.getGuardianId())
                == null) {
            throw ApiException.forbidden("当前监护关系已失效，不能撤回请假");
        }
        SessionWorkflowContext lockedSession = lockSession(initial);
        LeaveRequestRecord locked = mapper.lockLeaveRecord(id);
        verifyLeaveIdentity(initial, locked, lockedSession);
        requireSameSchool(principal, lockedSession.getSchoolId());
        validateLeaveSubmissionWindow(lockedSession);
        if (!"PENDING".equals(locked.getStatus())) {
            throw ApiException.conflict(
                    "LEAVE_NOT_PENDING", "只有待审核请假可以撤回");
        }
        if (mapper.withdrawLeave(id, principal.guardianId(), principal.id()) != 1) {
            throw ApiException.conflict("LEAVE_CHANGED", "请假状态已变化，请刷新后重试");
        }
        return requireLeaveView(id);
    }

    @Transactional
    public Map<String, Object> reviewLeave(
            long id, LeaveCorrectionController.LeaveReview request) {
        if (!List.of("APPROVED", "REJECTED").contains(request.decision())) {
            throw ApiException.badRequest(
                    "INVALID_LEAVE_DECISION", "请假审核决定无效");
        }
        LeaveRequestRecord initial = mapper.findLeaveRecord(id);
        if (initial == null) {
            throw ApiException.notFound("请假申请不存在");
        }
        PlatformPrincipal principal = currentUser.principal();
        requireTeachingWriteAccess(principal, initial, true);
        if ("APPROVED".equals(request.decision())
                && mapper.lockGuardianStudentBinding(
                                initial.getSchoolId(),
                                initial.getStudentId(),
                                initial.getGuardianId())
                        == null) {
            throw ApiException.conflict(
                    "LEAVE_ENROLLMENT_INACTIVE",
                    "学生报名或当前监护关系已失效，不能批准请假");
        }
        SessionWorkflowContext lockedSession = lockSession(initial);
        requireTeachingWriteAccess(principal, lockedSession, true);
        if ("APPROVED".equals(request.decision())) {
            validateLeaveReviewable(lockedSession);
            if (mapper.lockReviewableLeaveEnrollment(
                            initial.getSchoolId(),
                            initial.getOfferingId(),
                            initial.getStudentId())
                    == null) {
                throw ApiException.conflict(
                        "LEAVE_ENROLLMENT_INACTIVE",
                        "学生报名或当前监护关系已失效，不能批准请假");
            }
        }
        LeaveRequestRecord locked = mapper.lockLeaveRecord(id);
        verifyLeaveIdentity(initial, locked, lockedSession);
        if (!"PENDING".equals(locked.getStatus())) {
            throw ApiException.conflict(
                    "LEAVE_NOT_PENDING", "只有待审核请假可以审核");
        }
        if (mapper.reviewLeave(
                        id,
                        request.decision(),
                        trimToNull(request.remark()),
                        principal.id())
                != 1) {
            throw ApiException.conflict("LEAVE_CHANGED", "请假状态已变化，请刷新后重试");
        }
        return requireLeaveView(id);
    }

    public List<Map<String, Object>> leaveRequests(
            Long requestedSchoolId, Long offeringId, Long sessionId, String status) {
        if (status != null
                && !List.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")
                        .contains(status)) {
            throw ApiException.badRequest(
                    "INVALID_LEAVE_STATUS", "请假状态筛选值无效");
        }
        PlatformPrincipal principal = currentUser.principal();
        Long schoolId;
        Long teacherId = null;
        Long guardianId = null;
        switch (principal.roleCode()) {
            case "REGULATOR" -> schoolId = requestedSchoolId;
            case "SCHOOL_ADMIN" -> {
                schoolId = scopedSchool(principal, requestedSchoolId);
            }
            case "TEACHER" -> {
                schoolId = scopedSchool(principal, requestedSchoolId);
                if (principal.teacherId() == null) {
                    throw ApiException.forbidden("当前教师账号缺少教师档案");
                }
                teacherId = principal.teacherId();
            }
            case "GUARDIAN" -> {
                schoolId = scopedSchool(principal, requestedSchoolId);
                if (principal.guardianId() == null) {
                    throw ApiException.forbidden("当前家长账号缺少家长档案");
                }
                guardianId = principal.guardianId();
            }
            default -> throw ApiException.forbidden("当前角色不能查看请假申请");
        }
        return mapper.listLeaveRequests(
                schoolId, teacherId, guardianId, offeringId, sessionId, status);
    }

    @Transactional
    public Map<String, Object> requestCorrection(
            LeaveCorrectionController.CorrectionSubmission request) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"TEACHER".equals(principal.roleCode()) || principal.teacherId() == null) {
            throw ApiException.forbidden("只有有效教师账号可以发起考勤纠错");
        }
        AttendanceCorrectionRecord initial =
                mapper.findCorrectionTarget(request.sessionId(), request.studentId());
        if (initial == null) {
            throw ApiException.notFound("该课次学生考勤不存在");
        }
        SessionWorkflowContext lockedSession = lockSession(initial);
        AttendanceCorrectionRecord attendance =
                mapper.lockAttendanceTarget(initial.getAttendanceId());
        verifyAttendanceIdentity(initial, attendance, lockedSession);
        requireTeachingWriteAccess(principal, lockedSession, false);
        if (!"COMPLETED".equals(lockedSession.getSessionStatus())) {
            throw ApiException.conflict(
                    "SESSION_NOT_COMPLETED", "只有已完成课次的考勤需要走纠错审批");
        }
        String requestedRemark = trimToNull(request.requestedRemark());
        if (request.requestedStatus().equals(attendance.getAttendanceStatus())
                && Objects.equals(requestedRemark, attendance.getAttendanceRemark())) {
            throw ApiException.badRequest(
                    "CORRECTION_NO_CHANGE", "纠错后的考勤状态和备注与当前记录相同");
        }
        if (mapper.countActiveCorrection(attendance.getAttendanceId()) > 0) {
            throw ApiException.conflict(
                    "ACTIVE_CORRECTION_EXISTS", "该考勤已有待审批纠错申请");
        }

        AttendanceCorrectionRecord correction = new AttendanceCorrectionRecord();
        correction.setSchoolId(lockedSession.getSchoolId());
        correction.setOfferingId(lockedSession.getOfferingId());
        correction.setSessionId(lockedSession.getSessionId());
        correction.setAttendanceId(attendance.getAttendanceId());
        correction.setStudentId(attendance.getStudentId());
        correction.setRequestedStatus(request.requestedStatus());
        correction.setRequestedRemark(requestedRemark);
        correction.setReason(request.reason().strip());
        if (mapper.insertCorrection(correction, principal.id()) != 1) {
            throw ApiException.conflict(
                    "CORRECTION_SUBMIT_FAILED", "考勤纠错申请未能保存");
        }
        return requireCorrectionView(correction.getId());
    }

    @Transactional
    public Map<String, Object> cancelCorrection(long id) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"TEACHER".equals(principal.roleCode()) || principal.teacherId() == null) {
            throw ApiException.forbidden("只有有效教师账号可以取消纠错申请");
        }
        AttendanceCorrectionRecord initial = mapper.findCorrectionRecord(id);
        if (initial == null) {
            throw ApiException.notFound("考勤纠错申请不存在");
        }
        SessionWorkflowContext lockedSession = lockSession(initial);
        AttendanceCorrectionRecord attendance =
                mapper.lockAttendanceTarget(initial.getAttendanceId());
        verifyAttendanceIdentity(initial, attendance, lockedSession);
        AttendanceCorrectionRecord locked = mapper.lockCorrectionRecord(id);
        verifyCorrectionIdentity(initial, locked);
        requireTeachingWriteAccess(principal, lockedSession, false);
        if (locked.getRequestedBy() != principal.id()) {
            throw ApiException.forbidden("只能取消本人发起的纠错申请");
        }
        if (!"PENDING".equals(locked.getStatus())) {
            throw ApiException.conflict(
                    "CORRECTION_NOT_PENDING", "只有待审批纠错申请可以取消");
        }
        if (mapper.cancelCorrection(id, principal.id(), principal.id()) != 1) {
            throw ApiException.conflict(
                    "CORRECTION_CHANGED", "纠错申请状态已变化，请刷新后重试");
        }
        return requireCorrectionView(id);
    }

    @Transactional
    public Map<String, Object> reviewCorrection(
            long id, LeaveCorrectionController.CorrectionReview request) {
        if (!List.of("APPROVED", "REJECTED").contains(request.decision())) {
            throw ApiException.badRequest(
                    "INVALID_CORRECTION_DECISION", "纠错审批决定无效");
        }
        PlatformPrincipal principal = currentUser.principal();
        if (!"SCHOOL_ADMIN".equals(principal.roleCode())
                || principal.schoolId() == null) {
            throw ApiException.forbidden("只有学校管理员可以审批考勤纠错");
        }
        AttendanceCorrectionRecord initial = mapper.findCorrectionRecord(id);
        if (initial == null) {
            throw ApiException.notFound("考勤纠错申请不存在");
        }
        SessionWorkflowContext lockedSession = lockSession(initial);
        AttendanceCorrectionRecord attendance =
                mapper.lockAttendanceTarget(initial.getAttendanceId());
        verifyAttendanceIdentity(initial, attendance, lockedSession);
        AttendanceCorrectionRecord locked = mapper.lockCorrectionRecord(id);
        verifyCorrectionIdentity(initial, locked);
        requireSameSchool(principal, lockedSession.getSchoolId());
        if (!"COMPLETED".equals(lockedSession.getSessionStatus())) {
            throw ApiException.conflict(
                    "SESSION_NOT_COMPLETED", "课次不再是已完成状态，不能审批纠错");
        }
        if (!"PENDING".equals(locked.getStatus())) {
            throw ApiException.conflict(
                    "CORRECTION_NOT_PENDING", "只有待审批纠错申请可以审批");
        }
        String reviewRemark = trimToNull(request.remark());
        if ("REJECTED".equals(request.decision())) {
            if (mapper.rejectCorrection(id, reviewRemark, principal.id()) != 1) {
                throw ApiException.conflict(
                        "CORRECTION_CHANGED", "纠错申请状态已变化，请刷新后重试");
            }
            return requireCorrectionView(id);
        }

        if (mapper.insertAttendanceRevision(locked, attendance, principal.id()) != 1
                || mapper.updateAttendanceFromCorrection(
                                locked.getAttendanceId(),
                                locked.getRequestedStatus(),
                                locked.getRequestedRemark(),
                                principal.id())
                        != 1
                || mapper.markCorrectionApplied(id, reviewRemark, principal.id()) != 1) {
            throw ApiException.conflict(
                    "CORRECTION_APPLY_FAILED", "考勤纠错未能完整应用，已回滚");
        }
        return requireCorrectionView(id);
    }

    public List<Map<String, Object>> corrections(
            Long requestedSchoolId, Long offeringId, Long sessionId, String status) {
        if (status != null
                && !List.of("PENDING", "REJECTED", "CANCELED", "APPLIED")
                        .contains(status)) {
            throw ApiException.badRequest(
                    "INVALID_CORRECTION_STATUS", "纠错状态筛选值无效");
        }
        PlatformPrincipal principal = currentUser.principal();
        Long schoolId;
        Long teacherId = null;
        switch (principal.roleCode()) {
            case "REGULATOR" -> schoolId = requestedSchoolId;
            case "SCHOOL_ADMIN" -> schoolId = scopedSchool(principal, requestedSchoolId);
            case "TEACHER" -> {
                schoolId = scopedSchool(principal, requestedSchoolId);
                if (principal.teacherId() == null) {
                    throw ApiException.forbidden("当前教师账号缺少教师档案");
                }
                teacherId = principal.teacherId();
            }
            default -> throw ApiException.forbidden("当前角色不能查看考勤纠错申请");
        }
        return mapper.listCorrections(
                schoolId, teacherId, offeringId, sessionId, status);
    }

    public List<Map<String, Object>> revisions(long attendanceId) {
        AttendanceCorrectionRecord attendance =
                mapper.findAttendanceTargetById(attendanceId);
        if (attendance == null) {
            throw ApiException.notFound("考勤记录不存在");
        }
        PlatformPrincipal principal = currentUser.principal();
        switch (principal.roleCode()) {
            case "REGULATOR" -> {
                // Regulatory accounts have read-only cross-school access.
            }
            case "SCHOOL_ADMIN" -> requireSameSchool(principal, attendance.getSchoolId());
            case "TEACHER" -> {
                requireSameSchool(principal, attendance.getSchoolId());
                if (principal.teacherId() == null
                        || principal.teacherId() != attendance.getTeacherId()) {
                    throw ApiException.forbidden("只能查看本人开班的考勤修订历史");
                }
            }
            case "GUARDIAN" -> {
                requireSameSchool(principal, attendance.getSchoolId());
                if (principal.guardianId() == null
                        || mapper.countGuardianAttendanceAccess(
                                        attendanceId, principal.guardianId())
                                != 1) {
                    throw ApiException.forbidden("只能查看已绑定学生的考勤修订历史");
                }
            }
            default -> throw ApiException.forbidden("当前角色不能查看考勤修订历史");
        }
        return mapper.listAttendanceRevisions(attendanceId);
    }

    private SessionWorkflowContext lockSession(SessionWorkflowContext initial) {
        SessionWorkflowContext offering =
                mapper.lockOfferingContext(initial.getOfferingId());
        if (offering == null) {
            throw ApiException.notFound("课次所属开班不存在");
        }
        SessionWorkflowContext session =
                mapper.lockSessionContext(initial.getSessionId());
        if (session == null) {
            throw ApiException.notFound("课次不存在");
        }
        if (session.getOfferingId() != initial.getOfferingId()
                || session.getSchoolId() != initial.getSchoolId()
                || offering.getSchoolId() != session.getSchoolId()) {
            throw ApiException.conflict(
                    "SESSION_CHANGED", "课次归属已变化，请刷新后重试");
        }
        session.setTeacherId(offering.getTeacherId());
        session.setOfferingStatus(offering.getOfferingStatus());
        return session;
    }

    private void validateLeaveSubmissionWindow(SessionWorkflowContext session) {
        validateLeaveReviewable(session);
        LocalDateTime startsAt =
                LocalDateTime.of(session.getSessionDate(), session.getStartTime());
        if (!LocalDateTime.now(clock).isBefore(startsAt)) {
            throw ApiException.conflict(
                    "LEAVE_DEADLINE_PASSED", "课次开始后不能提交或撤回请假");
        }
    }

    private void validateLeaveReviewable(SessionWorkflowContext session) {
        if (!"SCHEDULED".equals(session.getSessionStatus())) {
            throw ApiException.conflict(
                    "SESSION_NOT_SCHEDULED", "只有尚未完成且未取消的课次可以审核请假");
        }
        if (!List.of("PUBLISHED", "CLOSED").contains(session.getOfferingStatus())) {
            throw ApiException.conflict(
                    "OFFERING_NOT_ACTIVE", "当前开班状态不能办理请假");
        }
    }

    private void requireTeachingWriteAccess(
            PlatformPrincipal principal,
            SessionWorkflowContext session,
            boolean allowSchoolAdmin) {
        requireSameSchool(principal, session.getSchoolId());
        if ("TEACHER".equals(principal.roleCode())
                && principal.teacherId() != null
                && principal.teacherId() == session.getTeacherId()) {
            return;
        }
        if (allowSchoolAdmin && "SCHOOL_ADMIN".equals(principal.roleCode())) {
            return;
        }
        throw ApiException.forbidden(
                allowSchoolAdmin ? "只能审核本人开班或本校请假" : "只能操作本人负责开班");
    }

    private PlatformPrincipal requireGuardian() {
        PlatformPrincipal principal = currentUser.principal();
        if (!"GUARDIAN".equals(principal.roleCode())
                || principal.guardianId() == null
                || principal.schoolId() == null) {
            throw ApiException.forbidden("当前账号不是有效家长账号");
        }
        return principal;
    }

    private Long scopedSchool(PlatformPrincipal principal, Long requestedSchoolId) {
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前账号缺少学校数据权限");
        }
        if (requestedSchoolId != null
                && requestedSchoolId.longValue() != principal.schoolId()) {
            throw ApiException.forbidden("不能访问其他学校的数据");
        }
        return principal.schoolId();
    }

    private void requireSameSchool(PlatformPrincipal principal, long schoolId) {
        if (principal.schoolId() == null || principal.schoolId() != schoolId) {
            throw ApiException.forbidden("不能访问其他学校的数据");
        }
    }

    private void verifyLeaveIdentity(
            LeaveRequestRecord initial,
            LeaveRequestRecord locked,
            SessionWorkflowContext session) {
        if (locked == null
                || locked.getSchoolId() != initial.getSchoolId()
                || locked.getOfferingId() != initial.getOfferingId()
                || locked.getSessionId() != initial.getSessionId()
                || locked.getStudentId() != initial.getStudentId()
                || locked.getGuardianId() != initial.getGuardianId()
                || session.getOfferingId() != locked.getOfferingId()) {
            throw ApiException.conflict(
                    "LEAVE_CHANGED", "请假申请归属已变化，请刷新后重试");
        }
    }

    private void verifyAttendanceIdentity(
            AttendanceCorrectionRecord initial,
            AttendanceCorrectionRecord attendance,
            SessionWorkflowContext session) {
        if (attendance == null
                || attendance.getSchoolId() != initial.getSchoolId()
                || attendance.getOfferingId() != initial.getOfferingId()
                || attendance.getSessionId() != initial.getSessionId()
                || attendance.getAttendanceId() != initial.getAttendanceId()
                || attendance.getStudentId() != initial.getStudentId()
                || session.getOfferingId() != attendance.getOfferingId()) {
            throw ApiException.conflict(
                    "ATTENDANCE_CHANGED", "考勤归属已变化，请刷新后重试");
        }
    }

    private void verifyCorrectionIdentity(
            AttendanceCorrectionRecord initial, AttendanceCorrectionRecord locked) {
        if (locked == null
                || locked.getSchoolId() != initial.getSchoolId()
                || locked.getOfferingId() != initial.getOfferingId()
                || locked.getSessionId() != initial.getSessionId()
                || locked.getAttendanceId() != initial.getAttendanceId()
                || locked.getStudentId() != initial.getStudentId()) {
            throw ApiException.conflict(
                    "CORRECTION_CHANGED", "纠错申请归属已变化，请刷新后重试");
        }
    }

    private Map<String, Object> requireLeaveView(long id) {
        Map<String, Object> view = mapper.findLeaveView(id);
        if (view == null) {
            throw ApiException.notFound("请假申请不存在");
        }
        return view;
    }

    private Map<String, Object> requireCorrectionView(long id) {
        Map<String, Object> view = mapper.findCorrectionView(id);
        if (view == null) {
            throw ApiException.notFound("考勤纠错申请不存在");
        }
        return view;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
