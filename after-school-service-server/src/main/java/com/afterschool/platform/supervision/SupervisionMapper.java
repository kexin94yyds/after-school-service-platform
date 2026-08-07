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
