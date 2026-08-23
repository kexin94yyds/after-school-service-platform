package com.afterschool.platform.registration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface EnrollmentMapper {

    List<Map<String, Object>> guardianStudents(@Param("guardianId") long guardianId);

    EnrollmentStudent findGuardianStudent(
            @Param("studentId") long studentId, @Param("guardianId") long guardianId);

    EnrollmentStudent lockGuardianStudent(
            @Param("studentId") long studentId, @Param("guardianId") long guardianId);

    EnrollmentStudent findStudent(@Param("studentId") long studentId);

    EnrollmentStudent lockStudent(@Param("studentId") long studentId);

    Map<String, Object> findStudentProfile(@Param("studentId") long studentId);

    Long lockSchoolStudent(
            @Param("studentId") long studentId,
            @Param("schoolId") long schoolId);

    EnrollmentOffering findOffering(@Param("offeringId") long offeringId);

    EnrollmentOffering lockOffering(@Param("offeringId") long offeringId);

    LocalDateTime findFirstValidSessionStart(@Param("offeringId") long offeringId);

    EnrollmentState findEnrollmentState(
            @Param("offeringId") long offeringId, @Param("studentId") long studentId);

    int countScheduleConflicts(
            @Param("studentId") long studentId, @Param("offeringId") long offeringId);

    int incrementCapacity(@Param("offeringId") long offeringId);

    int decrementCapacity(@Param("offeringId") long offeringId);

    int insertEnrollment(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("guardianId") Long guardianId);

    int reactivateEnrollment(
            @Param("id") long id, @Param("guardianId") Long guardianId);

    int cancelEnrollment(
            @Param("id") long id,
            @Param("guardianId") Long guardianId,
            @Param("schoolId") Long schoolId,
            @Param("canceledBy") long canceledBy);

    int withdrawActiveLeavesForEnrollment(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("withdrawnBy") long withdrawnBy);

    EnrollmentRecord findScopedEnrollment(
            @Param("id") long id, @Param("guardianId") long guardianId);

    EnrollmentRecord findSchoolEnrollment(
            @Param("id") long id,
            @Param("schoolId") long schoolId);

    EnrollmentRecord findEnrollment(@Param("id") long id);

    EnrollmentRecord findStudentEnrollment(
            @Param("id") long id, @Param("studentId") long studentId);

    EnrollmentRecord lockScopedEnrollment(
            @Param("id") long id, @Param("guardianId") long guardianId);

    EnrollmentRecord lockSchoolEnrollment(
            @Param("id") long id, @Param("schoolId") long schoolId);

    EnrollmentRecord lockStudentEnrollment(
            @Param("id") long id, @Param("studentId") long studentId);

    Map<String, Object> findEnrollmentView(
            @Param("offeringId") long offeringId, @Param("studentId") long studentId);

    List<Map<String, Object>> listEnrollments(
            @Param("schoolId") Long schoolId,
            @Param("teacherId") Long teacherId,
            @Param("guardianId") Long guardianId,
            @Param("studentId") Long studentId);

    List<Map<String, Object>> listGuardianOfferings(@Param("schoolId") long schoolId);

    List<Map<String, Object>> listGuardianAttendance(@Param("studentId") long studentId);

    List<Map<String, Object>> listStudentSchedule(@Param("studentId") long studentId);

    List<Map<String, Object>> listGuardianAttendanceByRange(
            @Param("studentId") long studentId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);

    Map<String, Object> guardianAttendanceSummary(
            @Param("studentId") long studentId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);

    List<Map<String, Object>> listEnrollmentRoster(
            @Param("offeringId") long offeringId,
            @Param("schoolId") Long schoolId);

    List<Map<String, Object>> listClassEnrollmentRoster(
            @Param("classId") long classId,
            @Param("schoolId") long schoolId);

    int insertEnrollmentAction(
            @Param("schoolId") long schoolId,
            @Param("enrollmentId") long enrollmentId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("guardianId") Long guardianId,
            @Param("actionType") String actionType,
            @Param("relatedEnrollmentId") Long relatedEnrollmentId,
            @Param("actorUserId") long actorUserId,
            @Param("actorRole") String actorRole);

    List<Map<String, Object>> listEnrollmentActions(
            @Param("enrollmentId") long enrollmentId,
            @Param("schoolId") Long schoolId,
            @Param("guardianId") Long guardianId,
            @Param("studentId") Long studentId);
}
