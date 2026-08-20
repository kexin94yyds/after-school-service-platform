package com.afterschool.platform.rectification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RectificationMapper {

    Map<String, Object> findAlert(
            @Param("alertId") long alertId,
            @Param("schoolId") Long schoolId);

    Map<String, Object> findNotice(
            @Param("alertId") long alertId,
            @Param("schoolId") Long schoolId);

    int upsertNotice(
            @Param("schoolId") long schoolId,
            @Param("alertId") long alertId,
            @Param("title") String title,
            @Param("requirements") String requirements,
            @Param("dueAt") LocalDateTime dueAt,
            @Param("issuedBy") long issuedBy);

    int updateAlertDeadline(
            @Param("alertId") long alertId,
            @Param("schoolId") long schoolId,
            @Param("dueAt") LocalDateTime dueAt);

    int insertAction(
            @Param("schoolId") long schoolId,
            @Param("alertId") long alertId,
            @Param("status") String status,
            @Param("actionType") String actionType,
            @Param("comment") String comment,
            @Param("actorId") long actorId,
            @Param("actorRole") String actorRole);

    int insertMaterial(
            @Param("schoolId") long schoolId,
            @Param("alertId") long alertId,
            @Param("noticeId") long noticeId,
            @Param("originalName") String originalName,
            @Param("objectKey") String objectKey,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("uploadedBy") long uploadedBy);

    Map<String, Object> findMaterialByObjectKey(
            @Param("objectKey") String objectKey,
            @Param("schoolId") Long schoolId);

    Map<String, Object> findMaterial(
            @Param("id") long id,
            @Param("schoolId") Long schoolId);

    List<Map<String, Object>> listMaterials(
            @Param("alertId") long alertId,
            @Param("schoolId") Long schoolId);
}
