package com.afterschool.platform.teaching;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TeachingMapper {

    OfferingSchedule findOfferingSchedule(@Param("offeringId") long offeringId);

    OfferingSchedule lockOfferingSchedule(@Param("offeringId") long offeringId);

    List<LocalDate> listClosedCalendarDates(
            @Param("schoolId") long schoolId,
            @Param("termId") long termId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<LocalDate> listAppliedRescheduleOriginalDates(
            @Param("offeringId") long offeringId);

    int insertSession(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("classroom") String classroom);

    List<Map<String, Object>> listSessions(@Param("offeringId") long offeringId);

    LessonOwner findLessonOwner(@Param("sessionId") long sessionId);

    Long findLessonOfferingId(@Param("sessionId") long sessionId);

    LessonOwner lockOfferingAccess(@Param("offeringId") long offeringId);

    LessonOwner lockLessonOwner(@Param("sessionId") long sessionId);

    int countSessionAttendance(@Param("sessionId") long sessionId);

    int updateSession(
            @Param("sessionId") long sessionId,
            @Param("status") String status,
            @Param("notes") String notes);

    List<Map<String, Object>> listAttendance(@Param("sessionId") long sessionId);

    int countAttendanceEligible(
            @Param("offeringId") long offeringId,
            @Param("sessionId") long sessionId,
            @Param("studentId") long studentId);

    int upsertAttendance(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("sessionId") long sessionId,
            @Param("studentId") long studentId,
            @Param("status") String status,
            @Param("recordedBy") long recordedBy,
            @Param("remark") String remark);
}
