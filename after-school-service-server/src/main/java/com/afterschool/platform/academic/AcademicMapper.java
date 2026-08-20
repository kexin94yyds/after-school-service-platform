package com.afterschool.platform.academic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AcademicMapper {

    List<Map<String, Object>> listTerms();

    Map<String, Object> findTermByCode(@Param("termCode") String termCode);

    AcademicTerm findTerm(@Param("id") long id);

    AcademicTerm lockTerm(@Param("id") long id);

    List<Long> lockTermOfferings(@Param("termId") long termId);

    int insertTerm(
            @Param("termCode") String termCode,
            @Param("termName") String termName,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") String status,
            @Param("createdBy") long createdBy);

    int updateTerm(
            @Param("id") long id,
            @Param("termCode") String termCode,
            @Param("termName") String termName,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") String status);

    int countTermPlans(@Param("termId") long termId);

    List<Map<String, Object>> listServicePlans(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId);

    Map<String, Object> findServicePlanByCode(
            @Param("schoolId") long schoolId,
            @Param("planCode") String planCode);

    ServicePlan findServicePlan(@Param("id") long id);

    ServicePlan lockServicePlan(
            @Param("id") long id,
            @Param("schoolId") long schoolId);

    List<Long> lockPlanOfferings(@Param("planId") long planId);

    int insertServicePlan(
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("planCode") String planCode,
            @Param("planName") String planName,
            @Param("description") String description,
            @Param("createdBy") long createdBy);

    int updateServicePlan(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("planCode") String planCode,
            @Param("planName") String planName,
            @Param("description") String description);

    int transitionServicePlan(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("reason") String reason,
            @Param("actorId") long actorId,
            @Param("transitionTime") LocalDateTime transitionTime);

    int countOtherActivePlans(
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("excludeId") long excludeId);

    int countPlanOfferingDependencies(@Param("planId") long planId);

    int countPlanItems(@Param("planId") long planId);

    List<Map<String, Object>> listServicePlanItems(
            @Param("planId") long planId,
            @Param("schoolId") Long schoolId);

    Map<String, Object> findServicePlanItem(
            @Param("id") long id,
            @Param("planId") long planId,
            @Param("schoolId") long schoolId);

    int insertServicePlanItem(
            @Param("schoolId") long schoolId,
            @Param("planId") long planId,
            @Param("category") String category,
            @Param("plannedCourseCount") int plannedCourseCount,
            @Param("plannedClassCount") int plannedClassCount,
            @Param("capacityPerClass") int capacityPerClass,
            @Param("plannedTeacherCount") int plannedTeacherCount,
            @Param("notes") String notes,
            @Param("createdBy") long createdBy);

    int updateServicePlanItem(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("planId") long planId,
            @Param("category") String category,
            @Param("plannedCourseCount") int plannedCourseCount,
            @Param("plannedClassCount") int plannedClassCount,
            @Param("capacityPerClass") int capacityPerClass,
            @Param("plannedTeacherCount") int plannedTeacherCount,
            @Param("notes") String notes);

    int deleteServicePlanItem(
            @Param("id") long id,
            @Param("planId") long planId,
            @Param("schoolId") long schoolId);

    List<Map<String, Object>> listRooms(@Param("schoolId") Long schoolId);

    Map<String, Object> findRoomByCode(
            @Param("schoolId") long schoolId,
            @Param("roomCode") String roomCode);

    RoomResource lockRoom(
            @Param("id") long id,
            @Param("schoolId") long schoolId);

    int insertRoom(
            @Param("schoolId") long schoolId,
            @Param("roomCode") String roomCode,
            @Param("roomName") String roomName,
            @Param("location") String location,
            @Param("capacity") int capacity,
            @Param("status") String status,
            @Param("createdBy") long createdBy);

    int updateRoom(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("roomCode") String roomCode,
            @Param("roomName") String roomName,
            @Param("location") String location,
            @Param("capacity") int capacity,
            @Param("status") String status);

    int countOpenRoomOfferings(@Param("roomId") long roomId);

    int countRoomCapacityViolations(
            @Param("roomId") long roomId,
            @Param("capacity") int capacity);

    List<Map<String, Object>> listCalendarEvents(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId);

    Map<String, Object> findCalendarEvent(
            @Param("id") long id,
            @Param("schoolId") long schoolId);

    Map<String, Object> findCalendarEventByDate(
            @Param("schoolId") long schoolId,
            @Param("eventDate") LocalDate eventDate);

    Long lockCalendarEvent(
            @Param("id") long id,
            @Param("schoolId") long schoolId);

    int insertCalendarEvent(
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("eventDate") LocalDate eventDate,
            @Param("dayType") String dayType,
            @Param("eventName") String eventName,
            @Param("description") String description,
            @Param("createdBy") long createdBy);

    int updateCalendarEvent(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("eventDate") LocalDate eventDate,
            @Param("dayType") String dayType,
            @Param("eventName") String eventName,
            @Param("description") String description);

    List<Long> lockCalendarOfferings(@Param("schoolId") long schoolId);

    List<SessionResource> lockCalendarSessions(
            @Param("schoolId") long schoolId,
            @Param("eventDate") LocalDate eventDate);

    int countCalendarSessionHistory(
            @Param("schoolId") long schoolId,
            @Param("sessionId") long sessionId);

    int cancelCalendarSession(
            @Param("schoolId") long schoolId,
            @Param("sessionId") long sessionId,
            @Param("now") LocalDateTime now);

    int countClosedCalendarDay(
            @Param("schoolId") long schoolId,
            @Param("sessionDate") LocalDate sessionDate);

    List<Map<String, Object>> listScheduleAdjustments(
            @Param("schoolId") Long schoolId,
            @Param("offeringId") Long offeringId,
            @Param("teacherId") Long teacherId);

    SessionResource findSessionResource(@Param("sessionId") long sessionId);

    SessionResource lockSessionResource(@Param("sessionId") long sessionId);

    Long lockTeacher(
            @Param("teacherId") long teacherId,
            @Param("schoolId") long schoolId);

    Long lockOffering(
            @Param("offeringId") long offeringId,
            @Param("schoolId") long schoolId);

    int countSessionAttendance(@Param("sessionId") long sessionId);

    Long findTeacherSessionConflict(
            @Param("teacherId") long teacherId,
            @Param("excludeSessionId") long excludeSessionId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    Long findRoomSessionConflict(
            @Param("schoolId") long schoolId,
            @Param("roomId") long roomId,
            @Param("excludeSessionId") long excludeSessionId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    List<Long> lockActiveEnrollmentStudentIds(@Param("offeringId") long offeringId);

    Long findStudentSessionConflict(
            @Param("studentId") long studentId,
            @Param("offeringId") long offeringId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    int insertScheduleAdjustment(
            @Param("schoolId") long schoolId,
            @Param("sessionId") long sessionId,
            @Param("originalSessionDate") LocalDate originalSessionDate,
            @Param("originalStartTime") LocalTime originalStartTime,
            @Param("originalEndTime") LocalTime originalEndTime,
            @Param("originalRoomId") Long originalRoomId,
            @Param("originalClassroom") String originalClassroom,
            @Param("adjustedSessionDate") LocalDate adjustedSessionDate,
            @Param("adjustedStartTime") LocalTime adjustedStartTime,
            @Param("adjustedEndTime") LocalTime adjustedEndTime,
            @Param("adjustedRoomId") long adjustedRoomId,
            @Param("adjustedClassroom") String adjustedClassroom,
            @Param("reason") String reason,
            @Param("requestedBy") long requestedBy,
            @Param("appliedAt") LocalDateTime appliedAt);

    int updateSessionSchedule(
            @Param("sessionId") long sessionId,
            @Param("schoolId") long schoolId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("roomId") Long roomId,
            @Param("classroom") String classroom);

    Map<String, Object> findLatestScheduleAdjustment(
            @Param("sessionId") long sessionId,
            @Param("requestedBy") long requestedBy);

    ScheduleAdjustmentResource findScheduleAdjustmentResource(
            @Param("adjustmentId") long adjustmentId);

    ScheduleAdjustmentResource lockScheduleAdjustmentResource(
            @Param("adjustmentId") long adjustmentId,
            @Param("schoolId") long schoolId);

    ScheduleAdjustmentResource lockLatestAppliedScheduleAdjustment(
            @Param("sessionId") long sessionId,
            @Param("schoolId") long schoolId);

    int markScheduleAdjustmentReverted(
            @Param("adjustmentId") long adjustmentId,
            @Param("schoolId") long schoolId);

    Map<String, Object> findScheduleAdjustmentView(
            @Param("adjustmentId") long adjustmentId,
            @Param("schoolId") long schoolId);

    Long findRoomOfferingConflict(
            @Param("schoolId") long schoolId,
            @Param("roomId") long roomId,
            @Param("excludeOfferingId") Long excludeOfferingId,
            @Param("weekDay") int weekDay,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
