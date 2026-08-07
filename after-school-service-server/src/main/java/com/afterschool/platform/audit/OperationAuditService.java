package com.afterschool.platform.audit;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationAuditService {

    private static final Set<String> MUTATING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private final OperationAuditMapper mapper;
    private final CurrentUser currentUser;

    public OperationAuditService(
            OperationAuditMapper mapper,
            CurrentUser currentUser) {
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    @Transactional
    public void record(AuditEvent event) {
        mapper.insert(event);
    }

    public List<Map<String, Object>> list(
            Long requestedSchoolId,
            Long actorUserId,
            String requestedMethod,
            String pathPrefix,
            LocalDateTime occurredFrom,
            LocalDateTime occurredTo) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"REGULATOR".equals(principal.roleCode())
                && !"SCHOOL_ADMIN".equals(principal.roleCode())) {
            throw ApiException.forbidden("当前角色不能查看操作审计");
        }
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        if (actorUserId != null && actorUserId <= 0) {
            throw ApiException.badRequest(
                    "INVALID_ACTOR",
                    "操作用户编号必须大于零");
        }
        String method = normalizeOptional(requestedMethod);
        if (method != null) {
            method = method.toUpperCase();
            if (!MUTATING_METHODS.contains(method)) {
                throw ApiException.badRequest(
                        "INVALID_HTTP_METHOD",
                        "审计方法仅支持 POST、PUT、PATCH、DELETE");
            }
        }
        String normalizedPath = normalizeOptional(pathPrefix);
        if (normalizedPath != null && !normalizedPath.startsWith("/api/")) {
            throw ApiException.badRequest(
                    "INVALID_PATH_PREFIX",
                    "审计路径必须以 /api/ 开头");
        }
        if (normalizedPath != null && normalizedPath.length() > 255) {
            throw ApiException.badRequest(
                    "INVALID_PATH_PREFIX",
                    "审计路径前缀不能超过 255 个字符");
        }
        if (occurredFrom != null
                && occurredTo != null
                && occurredFrom.isAfter(occurredTo)) {
            throw ApiException.badRequest(
                    "INVALID_DATE_RANGE",
                    "开始时间不能晚于结束时间");
        }
        return mapper.list(
                schoolId,
                actorUserId,
                method,
                normalizedPath,
                occurredFrom,
                occurredTo);
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
