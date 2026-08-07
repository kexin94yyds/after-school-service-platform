package com.afterschool.platform.registration;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface EnrollmentMapper {

    List<Map<String, Object>> guardianStudents(@Param("guardianId") long guardianId);

    EnrollmentStudent findGuardianStudent(
            @Param("studentId") long studentId, @Param("guardianId") long guardianId);

    EnrollmentStudent lockGuardianStudent(
            @Param("studentId") long studentId, @Param("guardianId") long guardianId);

    Long lockSchoolStudent(
            @Param("studentId") long studentId,
            @Param("schoolId") long schoolId);

    EnrollmentOffering findOffering(@Param("offeringId") long offeringId);

    EnrollmentOffering lockOffering(@Param("offeringId") long offeringId);

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
            @Param("guardianId") long guardianId);

    int reactivateEnrollment(
            @Param("id") long id, @Param("guardianId") long guardianId);

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

    EnrollmentRecord lockScopedEnrollment(
            @Param("id") long id, @Param("guardianId") long guardianId);

    EnrollmentRecord lockSchoolEnrollment(
            @Param("id") long id, @Param("schoolId") long schoolId);

    Map<String, Object> findEnrollmentView(
            @Param("offeringId") long offeringId, @Param("studentId") long studentId);

    List<Map<String, Object>> listEnrollments(
            @Param("schoolId") Long schoolId,
            @Param("teacherId") Long teacherId,
            @Param("guardianId") Long guardianId);

    List<Map<String, Object>> listGuardianOfferings(@Param("schoolId") long schoolId);

    List<Map<String, Object>> listGuardianAttendance(@Param("studentId") long studentId);
}
