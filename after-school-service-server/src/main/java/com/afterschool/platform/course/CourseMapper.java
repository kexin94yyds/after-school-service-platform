package com.afterschool.platform.course;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface CourseMapper {

    List<Map<String, Object>> listCourses(@Param("schoolId") Long schoolId);

    Map<String, Object> findCourseByCode(
            @Param("schoolId") long schoolId, @Param("courseCode") String courseCode);

    int insertCourse(
            @Param("schoolId") long schoolId,
            @Param("courseCode") String courseCode,
            @Param("courseName") String courseName,
            @Param("category") String category,
            @Param("description") String description,
            @Param("targetGradeMin") int targetGradeMin,
            @Param("targetGradeMax") int targetGradeMax,
            @Param("defaultCapacity") int defaultCapacity,
            @Param("status") String status,
            @Param("createdBy") long createdBy);

    int updateCourse(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("courseCode") String courseCode,
            @Param("courseName") String courseName,
            @Param("category") String category,
            @Param("description") String description,
            @Param("targetGradeMin") int targetGradeMin,
            @Param("targetGradeMax") int targetGradeMax,
            @Param("defaultCapacity") int defaultCapacity,
            @Param("status") String status);

    Long lockCourse(@Param("id") long id, @Param("schoolId") long schoolId);

    Long lockActiveCourse(@Param("id") long id, @Param("schoolId") long schoolId);

    int countCourseDependencies(@Param("id") long id);

    int countCourseRuleDifferences(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("courseCode") String courseCode,
            @Param("targetGradeMin") int targetGradeMin,
            @Param("targetGradeMax") int targetGradeMax);

    int teacherExists(@Param("id") long id, @Param("schoolId") long schoolId);

    Long lockTeacher(@Param("id") long id, @Param("schoolId") long schoolId);

    String offeringStatus(@Param("id") long id, @Param("schoolId") long schoolId);

    int countScheduledSessions(@Param("id") long id);

    int countStartedScheduledSessions(
            @Param("id") long id,
            @Param("now") LocalDateTime now);

    int countOfferingDependencies(@Param("id") long id);

    int countOfferingShapeDifferences(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("courseId") long courseId,
            @Param("teacherId") long teacherId,
            @Param("offeringCode") String offeringCode,
            @Param("term") String term,
            @Param("weekDay") int weekDay,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("enrollmentStart") LocalDateTime enrollmentStart,
            @Param("enrollmentEnd") LocalDateTime enrollmentEnd,
            @Param("classroom") String classroom,
            @Param("termId") Long termId,
            @Param("planId") Long planId,
            @Param("roomId") Long roomId);

    Long findTeacherConflict(
            @Param("teacherId") long teacherId,
            @Param("excludeOfferingId") Long excludeOfferingId,
            @Param("weekDay") int weekDay,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<Map<String, Object>> listOfferings(
            @Param("schoolId") Long schoolId, @Param("teacherId") Long teacherId);

    Map<String, Object> findOfferingByCode(
            @Param("schoolId") long schoolId, @Param("offeringCode") String offeringCode);

    int insertOffering(
            @Param("schoolId") long schoolId,
            @Param("courseId") long courseId,
            @Param("teacherId") long teacherId,
            @Param("offeringCode") String offeringCode,
            @Param("term") String term,
            @Param("weekDay") int weekDay,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("enrollmentStart") LocalDateTime enrollmentStart,
            @Param("enrollmentEnd") LocalDateTime enrollmentEnd,
            @Param("capacity") int capacity,
            @Param("classroom") String classroom,
            @Param("status") String status,
            @Param("termId") Long termId,
            @Param("planId") Long planId,
            @Param("roomId") Long roomId);

    int updateOffering(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("courseId") long courseId,
            @Param("teacherId") long teacherId,
            @Param("offeringCode") String offeringCode,
            @Param("term") String term,
            @Param("weekDay") int weekDay,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("enrollmentStart") LocalDateTime enrollmentStart,
            @Param("enrollmentEnd") LocalDateTime enrollmentEnd,
            @Param("capacity") int capacity,
            @Param("classroom") String classroom,
            @Param("status") String status,
            @Param("termId") Long termId,
            @Param("planId") Long planId,
            @Param("roomId") Long roomId);

    int cancelActiveEnrollments(
            @Param("id") long id,
            @Param("canceledBy") long canceledBy);

    int cancelScheduledSessions(
            @Param("id") long id,
            @Param("now") LocalDateTime now);
}
