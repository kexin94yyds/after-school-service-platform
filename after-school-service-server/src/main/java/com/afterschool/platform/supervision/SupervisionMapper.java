package com.afterschool.platform.supervision;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface SupervisionMapper {

    List<AlertDraft> findOverdueAttendanceCandidates(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("now") LocalDateTime now);

    List<AlertDraft> findOfferingsWithoutSessions(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("now") LocalDateTime now);

    List<AlertDraft> findLowAttendanceCandidates(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("threshold") BigDecimal threshold);

    int insertAlert(
            @Param("draft") AlertDraft draft,
            @Param("deadline") LocalDateTime deadline,
            @Param("scanRunId") String scanRunId);

    AlertIdentity findAlertIdentityByDedup(
            @Param("schoolId") long schoolId,
            @Param("alertType") String alertType,
            @Param("dedupKey") String dedupKey);

    int insertAction(
            @Param("schoolId") long schoolId,
            @Param("alertId") long alertId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("actionType") String actionType,
            @Param("comment") String comment,
            @Param("actorId") long actorId,
            @Param("actorRole") String actorRole);

    int insertSystemAction(
            @Param("schoolId") long schoolId,
            @Param("alertId") long alertId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("actionType") String actionType,
            @Param("comment") String comment);

    int insertScanRun(
            @Param("id") String id,
            @Param("triggerSource") String triggerSource,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("operatorUserId") Long operatorUserId);

    int insertFailedScanRun(
            @Param("id") String id,
            @Param("triggerSource") String triggerSource,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("failureSummary") String failureSummary,
            @Param("operatorUserId") Long operatorUserId);

    int completeScanRun(
            @Param("id") String id,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("candidateCount") int candidateCount,
            @Param("createdCount") int createdCount);

    int failScanRun(
            @Param("id") String id,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("failureSummary") String failureSummary);

    int markStaleScanRunsFailed(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("failureSummary") String failureSummary);

    int countRunningScanRuns();

    List<Map<String, Object>> listScanRuns(@Param("limit") int limit);

    List<Map<String, Object>> listAlerts(
            @Param("schoolId") Long schoolId,
            @Param("alertType") String alertType,
            @Param("status") String status,
            @Param("detectedFrom") LocalDateTime detectedFrom,
            @Param("detectedTo") LocalDateTime detectedTo);

    SupervisionAlert lockAlert(
            @Param("id") long id,
            @Param("schoolId") Long schoolId);

    SupervisionAlert findAlert(
            @Param("id") long id,
            @Param("schoolId") Long schoolId);

    int updateStatus(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    List<Map<String, Object>> listActions(
            @Param("alertId") long alertId,
            @Param("schoolId") Long schoolId);
}
