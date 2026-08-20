package com.afterschool.platform.evaluation;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    private final EvaluationMapper mapper;
    private final CurrentUser currentUser;

    public EvaluationService(
            EvaluationMapper mapper,
            CurrentUser currentUser) {
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    Map<String, Object> submit(
            long studentId,
            long offeringId,
            int rating,
            String requestedComment) {
        return submit(studentId, offeringId, rating, rating, requestedComment);
    }

    @Transactional
    public Map<String, Object> submit(
            long studentId,
            long offeringId,
            Integer courseRating,
            Integer teacherRating,
            String requestedComment) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"GUARDIAN".equals(principal.roleCode())
                || principal.guardianId() == null) {
            throw ApiException.forbidden("只有有效家长账号可以提交课程评价");
        }
        if (courseRating == null || courseRating < 1 || courseRating > 5
                || teacherRating == null || teacherRating < 1 || teacherRating > 5) {
            throw ApiException.badRequest(
                    "INVALID_RATING",
                    "课程评分和教师评分必须在 1 到 5 之间");
        }
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前家长账号没有学校数据权限");
        }
        long schoolId = principal.schoolId();
        // Global write lock order is offering -> student -> enrollment.
        // Re-checking eligibility after both parent rows are locked prevents
        // cancellation and offering shutdown from racing evaluation creation.
        if (mapper.lockOffering(offeringId, schoolId) == null
                || mapper.lockStudent(studentId, schoolId) == null) {
            throw ApiException.badRequest(
                    "EVALUATION_NOT_ELIGIBLE",
                    "仅可评价已有效报名且已有完成课次的绑定学生课程");
        }
        EvaluationEligibility eligibility = mapper.lockEligibility(
                studentId,
                offeringId,
                principal.guardianId(),
                schoolId);
        if (eligibility == null) {
            throw ApiException.badRequest(
                    "EVALUATION_NOT_ELIGIBLE",
                    "仅可评价已有效报名且已有完成课次的绑定学生课程");
        }
        if (mapper.countEvaluation(offeringId, studentId) > 0) {
            throw ApiException.conflict(
                    "EVALUATION_ALREADY_SUBMITTED",
                    "该学生已经评价过此课程");
        }
        String comment = normalizeComment(requestedComment);
        try {
            if (mapper.insertEvaluation(
                            eligibility.getSchoolId(),
                            offeringId,
                            eligibility.getEnrollmentId(),
                            studentId,
                            principal.guardianId(),
                            courseRating,
                            teacherRating,
                            comment)
                    != 1) {
                throw ApiException.conflict(
                        "EVALUATION_NOT_CREATED",
                        "评价提交失败，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict(
                    "EVALUATION_ALREADY_SUBMITTED",
                    "该学生已经评价过此课程");
        }
        Map<String, Object> created =
                mapper.findEvaluation(offeringId, studentId);
        if (created == null) {
            throw ApiException.conflict(
                    "EVALUATION_NOT_CREATED",
                    "评价提交结果不可用，请刷新后重试");
        }
        return created;
    }

    public List<Map<String, Object>> mine() {
        PlatformPrincipal principal = currentUser.principal();
        if (!"GUARDIAN".equals(principal.roleCode())
                || principal.guardianId() == null) {
            throw ApiException.forbidden("只有有效家长账号可以查看本人课程评价");
        }
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前家长账号没有学校数据权限");
        }
        return mapper.listGuardianEvaluations(
                principal.guardianId(), principal.schoolId());
    }

    public List<Map<String, Object>> list(
            Long requestedSchoolId,
            Long termId,
            String category,
            Integer minRating,
            Integer maxRating,
            LocalDateTime submittedFrom,
            LocalDateTime submittedTo) {
        Long schoolId = reportScope(requestedSchoolId);
        validateFilters(
                termId, minRating, maxRating, submittedFrom, submittedTo);
        List<Map<String, Object>> rows = mapper.listEvaluations(
                schoolId,
                termId,
                normalizeOptional(category),
                minRating,
                maxRating,
                submittedFrom,
                submittedTo);
        if (!"REGULATOR".equals(
                currentUser.principal().roleCode())) {
            return rows;
        }
        return rows.stream()
                .map(this::regulatorProjection)
                .toList();
    }

    public Map<String, Object> summary(
            Long requestedSchoolId,
            Long termId,
            String category,
            LocalDateTime submittedFrom,
            LocalDateTime submittedTo) {
        Long schoolId = reportScope(requestedSchoolId);
        validateFilters(termId, null, null, submittedFrom, submittedTo);
        return mapper.evaluationSummary(
                schoolId,
                termId,
                normalizeOptional(category),
                submittedFrom,
                submittedTo);
    }

    private Long reportScope(Long requestedSchoolId) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"REGULATOR".equals(principal.roleCode())
                && !"SCHOOL_ADMIN".equals(principal.roleCode())) {
            throw ApiException.forbidden("当前角色不能查看课程评价");
        }
        return currentUser.optionalSchoolScope(requestedSchoolId);
    }

    private void validateFilters(
            Long termId,
            Integer minRating,
            Integer maxRating,
            LocalDateTime from,
            LocalDateTime to) {
        if (termId != null && termId <= 0) {
            throw ApiException.badRequest("INVALID_TERM", "学期编号必须大于零");
        }
        if (minRating != null && (minRating < 1 || minRating > 5)
                || maxRating != null && (maxRating < 1 || maxRating > 5)
                || minRating != null && maxRating != null
                    && minRating > maxRating) {
            throw ApiException.badRequest(
                    "INVALID_RATING_RANGE",
                    "评分范围必须在 1 到 5 之间且起始值不大于结束值");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest(
                    "INVALID_DATE_RANGE",
                    "开始时间不能晚于结束时间");
        }
    }

    private String normalizeComment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 1000) {
            throw ApiException.badRequest(
                    "COMMENT_TOO_LONG",
                    "评价内容不能超过 1000 个字符");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Map<String, Object> regulatorProjection(
            Map<String, Object> source) {
        Map<String, Object> redacted = new LinkedHashMap<>(source);
        redacted.remove("studentId");
        redacted.remove("studentNo");
        redacted.remove("studentName");
        redacted.remove("guardianId");
        redacted.remove("guardianName");
        redacted.remove("comment");
        return redacted;
    }
}
