package com.afterschool.platform.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OperationAuditMapper {

    int insert(AuditEvent event);

    List<Map<String, Object>> list(
            @Param("schoolId") Long schoolId,
            @Param("actorUserId") Long actorUserId,
            @Param("httpMethod") String httpMethod,
            @Param("pathPrefix") String pathPrefix,
            @Param("occurredFrom") LocalDateTime occurredFrom,
            @Param("occurredTo") LocalDateTime occurredTo);
}
