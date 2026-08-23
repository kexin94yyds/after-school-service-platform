package com.afterschool.platform.leavecorrection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface LeaveCorrectionMapper {

    SessionWorkflowContext findSessionContext(@Param("sessionId") long sessionId);

    SessionWorkflowContext lockOfferingContext(@Param("offeringId") long offeringId);

    SessionWorkflowContext lockSessionContext(@Param("sessionId") long sessionId);

    Long lockGuardianStudentBinding(
            @Param("schoolId") long schoolId,
            @Param("studentId") long studentId,
            @Param("guardianId") long guardianId);

    List<Map<String, Object>> listGuardianLeaveSessions(
            @Param("studentId") long studentId,
            @Param("guardianId") long guardianId,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> listStudentLeaveSessions(
            @Param("studentId") long studentId,
            @Param("now") LocalDateTime now);

    Long lockGuardianEligibleEnrollment(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("sessionStartsAt") LocalDateTime sessionStartsAt);

    int countActiveLeave(
            @Param("sessionId") long sessionId, @Param("studentId") long studentId);

    int insertLeave(
            @Param("record") LeaveRequestRecord record,
            @Param("reason") String reason,
            @Param("submittedBy") long submittedBy);

    LeaveRequestRecord findLeaveRecord(@Param("id") long id);

    LeaveRequestRecord lockLeaveRecord(@Param("id") long id);

    Long lockReviewableLeaveEnrollment(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    int withdrawLeave(
            @Param("id") long id,
            @Param("studentId") long studentId,
            @Param("withdrawnBy") long withdrawnBy);

    int reviewLeave(
            @Param("id") long id,
            @Param("decision") String decision,
            @Param("reviewRemark") String reviewRemark,
            @Param("reviewedBy") long reviewedBy);

    Map<String, Object> findLeaveView(@Param("id") long id);

    List<Map<String, Object>> listLeaveRequests(
            @Param("schoolId") Long schoolId,
            @Param("teacherId") Long teacherId,
            @Param("guardianId") Long guardianId,
            @Param("studentId") Long studentId,
            @Param("offeringId") Long offeringId,
            @Param("sessionId") Long sessionId,
            @Param("status") String status);

    AttendanceCorrectionRecord findCorrectionTarget(
            @Param("sessionId") long sessionId, @Param("studentId") long studentId);

    AttendanceCorrectionRecord findAttendanceTargetById(
            @Param("attendanceId") long attendanceId);

    AttendanceCorrectionRecord lockAttendanceTarget(@Param("attendanceId") long attendanceId);

    int countActiveCorrection(@Param("attendanceId") long attendanceId);

    int insertCorrection(
            @Param("record") AttendanceCorrectionRecord record,
            @Param("requestedBy") long requestedBy);

    AttendanceCorrectionRecord findCorrectionRecord(@Param("id") long id);

    AttendanceCorrectionRecord lockCorrectionRecord(@Param("id") long id);

    int cancelCorrection(
            @Param("id") long id,
            @Param("requestedBy") long requestedBy,
            @Param("canceledBy") long canceledBy);

    int rejectCorrection(
            @Param("id") long id,
            @Param("reviewRemark") String reviewRemark,
            @Param("reviewedBy") long reviewedBy);

    int insertAttendanceRevision(
            @Param("request") AttendanceCorrectionRecord request,
            @Param("attendance") AttendanceCorrectionRecord attendance,
            @Param("changedBy") long changedBy);

    int updateAttendanceFromCorrection(
            @Param("attendanceId") long attendanceId,
            @Param("status") String status,
            @Param("remark") String remark,
            @Param("recordedBy") long recordedBy);

    int markCorrectionApplied(
            @Param("id") long id,
            @Param("reviewRemark") String reviewRemark,
            @Param("reviewedBy") long reviewedBy);

    Map<String, Object> findCorrectionView(@Param("id") long id);

    List<Map<String, Object>> listCorrections(
            @Param("schoolId") Long schoolId,
            @Param("teacherId") Long teacherId,
            @Param("offeringId") Long offeringId,
            @Param("sessionId") Long sessionId,
            @Param("status") String status);

    int countGuardianAttendanceAccess(
            @Param("attendanceId") long attendanceId,
            @Param("guardianId") long guardianId);

    int countStudentAttendanceAccess(
            @Param("attendanceId") long attendanceId,
            @Param("studentId") long studentId);

    List<Map<String, Object>> listAttendanceRevisions(
            @Param("attendanceId") long attendanceId);
}
