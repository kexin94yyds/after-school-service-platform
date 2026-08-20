package com.afterschool.platform.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ReportMapper {

    Map<String, Object> overview(@Param("schoolId") Long schoolId);

    List<Map<String, Object>> schoolBreakdown(@Param("schoolId") Long schoolId);

    List<Map<String, Object>> categoryBreakdown(@Param("schoolId") Long schoolId);

    List<Map<String, Object>> attendanceBreakdown(@Param("schoolId") Long schoolId);

    List<Map<String, Object>> teacherHours(@Param("schoolId") Long schoolId);

    Map<String, Object> satisfactionSummary(@Param("schoolId") Long schoolId);

    List<Map<String, Object>> coursePerformance(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("category") String category,
            @Param("status") String status);

    List<Map<String, Object>> rectificationBreakdown(
            @Param("schoolId") Long schoolId,
            @Param("detectedFrom") LocalDate detectedFrom,
            @Param("detectedTo") LocalDate detectedTo);
}
