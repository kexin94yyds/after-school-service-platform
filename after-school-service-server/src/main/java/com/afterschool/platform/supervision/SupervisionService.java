package com.afterschool.platform.supervision;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.audit.AuditTargetContext;
import com.afterschool.platform.common.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupervisionService {

    private static final Set<String> ALERT_TYPES = Set.of(
            "OVERDUE_ATTENDANCE",
            "OFFERING_NO_SESSIONS",
            "LOW_ATTENDANCE");
    private static final Set<String> STATUSES = Set.of(
            "OPEN",
            "ACKNOWLEDGED",
            "RECTIFYING",
            "WAITING_VERIFY",
            "CLOSED",
            "RETURNED");

    private final SupervisionMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public SupervisionService(
            SupervisionMapper mapper,
            CurrentUser currentUser,
            Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> scan(
            Long requestedSchoolId,
            Long termId,
            BigDecimal lowAttendanceThreshold,
            Integer deadlineDays) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"REGULATOR".equals(principal.roleCode())) {
            throw ApiException.forbidden("只有监管账号可以执行预警扫描");
        }
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        if (termId != null && termId <= 0) {
            throw ApiException.badRequest("INVALID_TERM", "学期编号必须大于零");
        }
        BigDecimal threshold = lowAttendanceThreshold == null
                ? new BigDecimal("0.8000")
                : lowAttendanceThreshold;
        if (threshold.compareTo(new BigDecimal("0.0100")) < 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest(
                    "INVALID_ATTENDANCE_THRESHOLD",
                    "低出勤率阈值必须在 0.01 到 1.00 之间");
        }
        int days = deadlineDays == null ? 7 : deadlineDays;
        if (days < 1 || days > 30) {
            throw ApiException.badRequest(
                    "INVALID_DEADLINE_DAYS",
                    "整改期限必须在 1 到 30 天之间");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<AlertDraft> candidates = new ArrayList<>();
        candidates.addAll(mapper.findOverdueAttendanceCandidates(
                schoolId, termId, now));
        candidates.addAll(mapper.findOfferingsWithoutSessions(
                schoolId, termId, now));
        candidates.addAll(mapper.findLowAttendanceCandidates(
                schoolId, termId, threshold));

        Map<String, Integer> createdByType = new LinkedHashMap<>();
        for (String type : ALERT_TYPES) {
            createdByType.put(type, 0);
        }
        int created = 0;
        LocalDateTime deadline = now.plusDays(days);
        String scanRunId = UUID.randomUUID().toString();
        Set<Long> recordedAlertIds = new HashSet<>();
        for (AlertDraft draft : candidates) {
            mapper.insertAlert(draft, deadline, scanRunId);
            AlertIdentity identity = mapper.findAlertIdentityByDedup(
                    draft.getSchoolId(),
                    draft.getAlertType(),
                    draft.getDedupKey());
            if (identity == null) {
                throw ApiException.conflict(
                        "ALERT_CREATION_RACE",
                        "预警生成状态已变化，请重新扫描");
            }
            if (!scanRunId.equals(identity.getScanRunId())
                    || !recordedAlertIds.add(identity.getId())) {
                continue;
            }
            mapper.insertAction(
                    draft.getSchoolId(),
                    identity.getId(),
                    null,
                    "OPEN",
                    "CREATE",
                    "监管扫描自动生成",
                    principal.id(),
                    principal.roleCode());
            created++;
            createdByType.computeIfPresent(
                    draft.getAlertType(), (ignored, count) -> count + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateCount", candidates.size());
        result.put("createdCount", created);
        result.put("deduplicatedCount", candidates.size() - created);
        result.put("createdByType", createdByType);
        result.put("scannedAt", now);
        result.put("lowAttendanceThreshold", threshold);
        return result;
    }

    public List<Map<String, Object>> list(
            Long requestedSchoolId,
            String alertType,
            String status,
            LocalDateTime detectedFrom,
            LocalDateTime detectedTo) {
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        String normalizedType = normalizeOptional(alertType);
        String normalizedStatus = normalizeOptional(status);
        if (normalizedType != null && !ALERT_TYPES.contains(normalizedType)) {
            throw ApiException.badRequest("INVALID_ALERT_TYPE", "预警类型不正确");
        }
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw ApiException.badRequest("INVALID_ALERT_STATUS", "预警状态不正确");
        }
        validateDateRange(detectedFrom, detectedTo);
        return mapper.listAlerts(
                schoolId,
                normalizedType,
                normalizedStatus,
                detectedFrom,
                detectedTo);
    }

    @Transactional
    public Map<String, Object> transition(
            long id,
            String requestedTarget,
            String requestedComment) {
        PlatformPrincipal principal = currentUser.principal();
        Long schoolScope = "REGULATOR".equals(principal.roleCode())
                ? null
                : principal.schoolId();
        if (schoolScope == null && !"REGULATOR".equals(principal.roleCode())) {
            throw ApiException.forbidden("当前账号没有学校数据权限");
        }
        String target = normalizeOptional(requestedTarget);
        if (target == null || !STATUSES.contains(target)) {
            throw ApiException.badRequest("INVALID_ALERT_STATUS", "目标状态不正确");
        }
        String comment = normalizeComment(requestedComment);

        SupervisionAlert alert = mapper.lockAlert(id, schoolScope);
        if (alert == null) {
            throw ApiException.notFound("预警不存在或不在当前数据范围内");
        }
        AuditTargetContext.setTargetSchoolId(alert.getSchoolId());
        String actionType = allowedAction(
                principal.roleCode(), alert.getStatus(), target);
        if (actionType == null) {
            throw ApiException.conflict(
                    "INVALID_ALERT_TRANSITION",
                    "当前角色不能执行该预警状态流转");
        }
        if (mapper.updateStatus(id, alert.getStatus(), target) != 1) {
            throw ApiException.conflict(
                    "ALERT_CHANGED",
                    "预警状态已变化，请刷新后重试");
        }
        mapper.insertAction(
                alert.getSchoolId(),
                id,
                alert.getStatus(),
                target,
                actionType,
                comment,
                principal.id(),
                principal.roleCode());
        return mapper.listAlerts(
                        alert.getSchoolId(), null, null, null, null)
                .stream()
                .filter(item -> ((Number) item.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("预警不存在"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        PlatformPrincipal principal = currentUser.principal();
        Long schoolScope = "REGULATOR".equals(principal.roleCode())
                ? null
                : principal.schoolId();
        SupervisionAlert alert = mapper.findAlert(id, schoolScope);
        if (alert == null) {
            throw ApiException.notFound("预警不存在或不在当前数据范围内");
        }
        return mapper.listActions(id, schoolScope);
    }

    private String allowedAction(String role, String from, String to) {
        if ("SCHOOL_ADMIN".equals(role)) {
            if ("OPEN".equals(from) && "ACKNOWLEDGED".equals(to)) {
                return "ACKNOWLEDGE";
            }
            if ("ACKNOWLEDGED".equals(from) && "RECTIFYING".equals(to)) {
                return "START_RECTIFICATION";
            }
            if ("RETURNED".equals(from) && "RECTIFYING".equals(to)) {
                return "RESUME_RECTIFICATION";
            }
            if ("RECTIFYING".equals(from) && "WAITING_VERIFY".equals(to)) {
                return "SUBMIT_VERIFICATION";
            }
        }
        if ("REGULATOR".equals(role) && "WAITING_VERIFY".equals(from)) {
            if ("CLOSED".equals(to)) {
                return "VERIFY_CLOSE";
            }
            if ("RETURNED".equals(to)) {
                return "RETURN_FOR_RECTIFICATION";
            }
        }
        return null;
    }

    private void validateDateRange(
            LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest(
                    "INVALID_DATE_RANGE",
                    "开始时间不能晚于结束时间");
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toUpperCase();
    }

    private String normalizeComment(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(
                    "COMMENT_REQUIRED",
                    "状态流转必须填写处理说明");
        }
        String normalized = value.strip();
        if (normalized.length() > 1000) {
            throw ApiException.badRequest(
                    "COMMENT_TOO_LONG",
                    "处理说明不能超过 1000 个字符");
        }
        return normalized;
    }
}
