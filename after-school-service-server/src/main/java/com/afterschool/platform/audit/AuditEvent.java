package com.afterschool.platform.audit;

public record AuditEvent(
        long actorUserId,
        String actorRole,
        Long actorSchoolId,
        Long targetSchoolId,
        String httpMethod,
        String requestPath,
        int responseStatus,
        String sourceFingerprint) {}
