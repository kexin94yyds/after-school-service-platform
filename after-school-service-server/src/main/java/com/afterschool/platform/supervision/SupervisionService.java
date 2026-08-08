package com.afterschool.platform.supervision;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.audit.AuditTargetContext;
import com.afterschool.platform.common.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SupervisionService {

    private static final Logger log =
            LoggerFactory.getLogger(SupervisionService.class);
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
    private static final BigDecimal DEFAULT_LOW_ATTENDANCE_THRESHOLD =
            new BigDecimal("0.8000");
    private static final int DEFAULT_DEADLINE_DAYS = 7;
    private static final int DEFAULT_SCAN_RUN_LIMIT = 50;
    private static final int MAX_SCAN_RUN_LIMIT = 200;
    private static final String STALE_RUN_FAILURE_SUMMARY =
            "扫描运行超时，已由后续扫描标记失败";
    private static final String REENTRANT_RUN_FAILURE_SUMMARY =
            "已有扫描正在执行，已拒绝重复启动";
    private static final String ROLLED_BACK_RUN_FAILURE_SUMMARY =
            "扫描事务未提交，运行已回滚";

    private final SupervisionMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final TransactionOperations requiresNewTransactions;
    private final Duration staleRunAfter;
    private final AtomicBoolean scanInProgress = new AtomicBoolean();

    @Autowired
    public SupervisionService(
            SupervisionMapper mapper,
            CurrentUser currentUser,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${app.supervision.scan.stale-after:PT2H}")
            Duration staleRunAfter) {
        this(
                mapper,
                currentUser,
                clock,
                requiresNewTransactions(transactionManager),
                staleRunAfter);
    }

    SupervisionService(
            SupervisionMapper mapper,
            CurrentUser currentUser,
            Clock clock,
            TransactionOperations requiresNewTransactions,
            Duration staleRunAfter) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
        this.requiresNewTransactions = requiresNewTransactions;
        if (staleRunAfter == null
                || staleRunAfter.isNegative()
                || staleRunAfter.isZero()) {
            throw new IllegalArgumentException("扫描运行超时时间必须大于零");
        }
        this.staleRunAfter = staleRunAfter;
    }

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
        return executeScan(
                TriggerSource.MANUAL,
                principal.id(),
                schoolId,
                validateScanParameters(
                        termId, lowAttendanceThreshold, deadlineDays));
    }

    /**
     * Scheduler-only entry point. It deliberately does not read CurrentUser
     * or create a synthetic human principal.
     */
    public void scanScheduled() {
        try {
            executeScan(
                    TriggerSource.SCHEDULED,
                    null,
                    null,
                    validateScanParameters(null, null, null));
        } catch (RuntimeException exception) {
            if (isScanAlreadyRunning(exception)) {
                log.warn(
                        "supervision_scan_scheduler_result source=SCHEDULED outcome=SKIPPED reason=ALREADY_RUNNING");
            } else {
                log.error(
                        "supervision_scan_scheduler_result source=SCHEDULED outcome=FAILED errorCategory={}",
                        failureCategory(exception));
            }
        }
    }

    public List<Map<String, Object>> listScanRuns(Integer requestedLimit) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"REGULATOR".equals(principal.roleCode())) {
            throw ApiException.forbidden("只有监管账号可以查看扫描运行记录");
        }
        int limit = requestedLimit == null
                ? DEFAULT_SCAN_RUN_LIMIT
                : requestedLimit;
        if (limit < 1 || limit > MAX_SCAN_RUN_LIMIT) {
            throw ApiException.badRequest(
                    "INVALID_SCAN_RUN_LIMIT",
                    "扫描运行记录数量必须在 1 到 200 之间");
        }
        return mapper.listScanRuns(limit);
    }

    private Map<String, Object> executeScan(
            TriggerSource triggerSource,
            Long operatorUserId,
            Long schoolId,
            ScanParameters parameters) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        if (!scanInProgress.compareAndSet(false, true)) {
            String rejectedRunId = recordRejectedScanRun(
                    triggerSource, operatorUserId, startedAt);
            log.warn(
                    "supervision_scan_rejected source={} runId={} reason=LOCAL_REENTRANT",
                    triggerSource,
                    rejectedRunId);
            throw ApiException.conflict(
                    "SCAN_ALREADY_RUNNING",
                    "已有预警扫描正在执行，请稍后重试");
        }

        ScanRun run = null;
        boolean releaseAfterTransactionCompletion = false;
        try {
            ScanAdmission admission = openScanRun(
                    triggerSource, operatorUserId, startedAt);
            if (!admission.accepted()) {
                log.warn(
                        "supervision_scan_rejected source={} runId={} reason=DATABASE_RUNNING",
                        triggerSource,
                        admission.rejectedRunId());
                throw ApiException.conflict(
                        "SCAN_ALREADY_RUNNING",
                        "已有预警扫描正在执行，请稍后重试");
            }
            ScanRun acceptedRun = admission.run();
            run = acceptedRun;

            ScanExecution execution;
            if (hasEnclosingTransaction()) {
                execution = performScan(acceptedRun, schoolId, parameters);
                registerCompletionAfterEnclosingTransaction(
                        acceptedRun, execution);
                releaseAfterTransactionCompletion = true;
            } else {
                execution = executeInNewTransaction(
                        () -> performScan(acceptedRun, schoolId, parameters));
                completeScanRun(
                        acceptedRun, execution, LocalDateTime.now(clock));
                logCompletedRun(acceptedRun, execution);
            }
            return execution.response();
        } catch (RuntimeException exception) {
            if (run != null) {
                failScanRun(
                        run,
                        LocalDateTime.now(clock),
                        failureSummary(exception));
            }
            if (!isScanAlreadyRunning(exception)) {
                log.error(
                        "supervision_scan_result source={} runId={} outcome=FAILED errorCategory={}",
                        triggerSource,
                        run == null ? null : run.id(),
                        failureCategory(exception));
            }
            throw exception;
        } finally {
            if (!releaseAfterTransactionCompletion) {
                scanInProgress.set(false);
            }
        }
    }

    private ScanAdmission openScanRun(
            TriggerSource triggerSource,
            Long operatorUserId,
            LocalDateTime startedAt) {
        try {
            ScanAdmission admission = requiresNewTransactions.execute(status -> {
                int staleRuns = mapper.markStaleScanRunsFailed(
                        startedAt.minus(staleRunAfter),
                        startedAt,
                        STALE_RUN_FAILURE_SUMMARY);
                if (staleRuns > 0) {
                    log.warn(
                            "supervision_scan_stale_runs_marked_failed count={}",
                            staleRuns);
                }
                if (mapper.countRunningScanRuns() > 0) {
                    String rejectedRunId = insertFailedScanRun(
                            triggerSource, operatorUserId, startedAt);
                    return ScanAdmission.rejected(rejectedRunId);
                }
                ScanRun run = new ScanRun(
                        UUID.randomUUID().toString(),
                        triggerSource,
                        operatorUserId,
                        startedAt);
                if (mapper.insertScanRun(
                        run.id(),
                        run.triggerSource().name(),
                        run.startedAt(),
                        run.operatorUserId()) != 1) {
                    throw new IllegalStateException("扫描运行记录未写入");
                }
                return ScanAdmission.accepted(run);
            });
            if (admission == null) {
                throw new IllegalStateException("扫描运行记录未返回");
            }
            return admission;
        } catch (DataIntegrityViolationException exception) {
            String rejectedRunId = recordRejectedScanRun(
                    triggerSource, operatorUserId, startedAt);
            return ScanAdmission.rejected(rejectedRunId);
        }
    }

    private String recordRejectedScanRun(
            TriggerSource triggerSource,
            Long operatorUserId,
            LocalDateTime startedAt) {
        String runId = UUID.randomUUID().toString();
        try {
            executeInNewTransaction(() -> {
                if (mapper.insertFailedScanRun(
                        runId,
                        triggerSource.name(),
                        startedAt,
                        LocalDateTime.now(clock),
                        REENTRANT_RUN_FAILURE_SUMMARY,
                        operatorUserId) != 1) {
                    throw new IllegalStateException("重复扫描拒绝记录未写入");
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.error(
                    "supervision_scan_rejection_record_failed source={} runId={} errorCategory={}",
                    triggerSource,
                    runId,
                    failureCategory(exception));
        }
        return runId;
    }

    private void registerCompletionAfterEnclosingTransaction(
            ScanRun run,
            ScanExecution execution) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        try {
                            if (status == STATUS_COMMITTED) {
                                completeScanRun(
                                        run,
                                        execution,
                                        LocalDateTime.now(clock));
                                logCompletedRun(run, execution);
                            } else {
                                failScanRun(
                                        run,
                                        LocalDateTime.now(clock),
                                        ROLLED_BACK_RUN_FAILURE_SUMMARY);
                                log.error(
                                        "supervision_scan_result source={} runId={} outcome=ROLLED_BACK",
                                        run.triggerSource(),
                                        run.id());
                            }
                        } catch (RuntimeException exception) {
                            log.error(
                                    "supervision_scan_completion_record_failed source={} runId={} errorCategory={}",
                                    run.triggerSource(),
                                    run.id(),
                                    failureCategory(exception));
                        } finally {
                            scanInProgress.set(false);
                        }
                    }
                });
    }

    private <T> T executeInNewTransaction(TransactionWork<T> work) {
        return requiresNewTransactions.execute(ignored -> work.execute());
    }

    private void completeScanRun(
            ScanRun run,
            ScanExecution execution,
            LocalDateTime finishedAt) {
        executeInNewTransaction(() -> {
            if (mapper.completeScanRun(
                    run.id(),
                    finishedAt,
                    execution.candidateCount(),
                    execution.createdCount()) != 1) {
                throw new IllegalStateException("扫描运行记录未能完成");
            }
            return null;
        });
    }

    private void failScanRun(
            ScanRun run,
            LocalDateTime finishedAt,
            String failureSummary) {
        try {
            executeInNewTransaction(() -> {
                int updated = mapper.failScanRun(
                        run.id(), finishedAt, failureSummary);
                if (updated != 1) {
                    log.warn(
                            "supervision_scan_failure_record_not_updated source={} runId={}",
                            run.triggerSource(),
                            run.id());
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.error(
                    "supervision_scan_failure_record_failed source={} runId={} errorCategory={}",
                    run.triggerSource(),
                    run.id(),
                    failureCategory(exception));
        }
    }

    private String insertFailedScanRun(
            TriggerSource triggerSource,
            Long operatorUserId,
            LocalDateTime startedAt) {
        String runId = UUID.randomUUID().toString();
        if (mapper.insertFailedScanRun(
                runId,
                triggerSource.name(),
                startedAt,
                startedAt,
                REENTRANT_RUN_FAILURE_SUMMARY,
                operatorUserId) != 1) {
            throw new IllegalStateException("重复扫描拒绝记录未写入");
        }
        return runId;
    }

    private ScanExecution performScan(
            ScanRun run,
            Long schoolId,
            ScanParameters parameters) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AlertDraft> candidates = new ArrayList<>();
        candidates.addAll(mapper.findOverdueAttendanceCandidates(
                schoolId, parameters.termId(), now));
        candidates.addAll(mapper.findOfferingsWithoutSessions(
                schoolId, parameters.termId(), now));
        candidates.addAll(mapper.findLowAttendanceCandidates(
                schoolId,
                parameters.termId(),
                parameters.lowAttendanceThreshold()));

        Map<String, Integer> createdByType = new LinkedHashMap<>();
        for (String type : ALERT_TYPES) {
            createdByType.put(type, 0);
        }
        int created = 0;
        LocalDateTime deadline = now.plusDays(parameters.deadlineDays());
        Set<Long> recordedAlertIds = new HashSet<>();
        for (AlertDraft draft : candidates) {
            mapper.insertAlert(draft, deadline, run.id());
            AlertIdentity identity = mapper.findAlertIdentityByDedup(
                    draft.getSchoolId(),
                    draft.getAlertType(),
                    draft.getDedupKey());
            if (identity == null) {
                throw ApiException.conflict(
                        "ALERT_CREATION_RACE",
                        "预警生成状态已变化，请重新扫描");
            }
            if (!run.id().equals(identity.getScanRunId())
                    || !recordedAlertIds.add(identity.getId())) {
                continue;
            }
            if (run.triggerSource() == TriggerSource.SCHEDULED) {
                mapper.insertSystemAction(
                        draft.getSchoolId(),
                        identity.getId(),
                        null,
                        "OPEN",
                        "CREATE",
                        "定时监管扫描自动生成");
            } else {
                mapper.insertAction(
                        draft.getSchoolId(),
                        identity.getId(),
                        null,
                        "OPEN",
                        "CREATE",
                        "监管扫描自动生成",
                        run.operatorUserId(),
                        "REGULATOR");
            }
            created++;
            createdByType.computeIfPresent(
                    draft.getAlertType(), (ignored, count) -> count + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanRunId", run.id());
        result.put("triggerSource", run.triggerSource().name());
        result.put("candidateCount", candidates.size());
        result.put("createdCount", created);
        result.put("deduplicatedCount", candidates.size() - created);
        result.put("createdByType", createdByType);
        result.put("scannedAt", now);
        result.put("lowAttendanceThreshold", parameters.lowAttendanceThreshold());
        return new ScanExecution(result, candidates.size(), created);
    }

    private ScanParameters validateScanParameters(
            Long termId,
            BigDecimal lowAttendanceThreshold,
            Integer deadlineDays) {
        if (termId != null && termId <= 0) {
            throw ApiException.badRequest("INVALID_TERM", "学期编号必须大于零");
        }
        BigDecimal threshold = lowAttendanceThreshold == null
                ? DEFAULT_LOW_ATTENDANCE_THRESHOLD
                : lowAttendanceThreshold;
        if (threshold.compareTo(new BigDecimal("0.0100")) < 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest(
                    "INVALID_ATTENDANCE_THRESHOLD",
                    "低出勤率阈值必须在 0.01 到 1.00 之间");
        }
        int days = deadlineDays == null
                ? DEFAULT_DEADLINE_DAYS
                : deadlineDays;
        if (days < 1 || days > 30) {
            throw ApiException.badRequest(
                    "INVALID_DEADLINE_DAYS",
                    "整改期限必须在 1 到 30 天之间");
        }
        return new ScanParameters(termId, threshold, days);
    }

    private boolean hasEnclosingTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private String failureSummary(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return "扫描未完成，业务错误代码：" + apiException.code();
        }
        if (exception instanceof DataAccessException) {
            return "扫描未完成，数据持久化失败";
        }
        return "扫描未完成，系统异常";
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return "API_" + apiException.code();
        }
        if (exception instanceof DataAccessException) {
            return "DATA_ACCESS";
        }
        return "INTERNAL";
    }

    private boolean isScanAlreadyRunning(RuntimeException exception) {
        return exception instanceof ApiException apiException
                && "SCAN_ALREADY_RUNNING".equals(apiException.code());
    }

    private void logCompletedRun(ScanRun run, ScanExecution execution) {
        log.info(
                "supervision_scan_result source={} runId={} outcome=SUCCESS candidateCount={} createdCount={}",
                run.triggerSource(),
                run.id(),
                execution.candidateCount(),
                execution.createdCount());
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

    private static TransactionOperations requiresNewTransactions(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private enum TriggerSource {
        MANUAL,
        SCHEDULED
    }

    private record ScanParameters(
            Long termId,
            BigDecimal lowAttendanceThreshold,
            int deadlineDays) {}

    private record ScanRun(
            String id,
            TriggerSource triggerSource,
            Long operatorUserId,
            LocalDateTime startedAt) {}

    private record ScanAdmission(
            ScanRun run,
            String rejectedRunId) {

        static ScanAdmission accepted(ScanRun run) {
            return new ScanAdmission(run, null);
        }

        static ScanAdmission rejected(String rejectedRunId) {
            return new ScanAdmission(null, rejectedRunId);
        }

        boolean accepted() {
            return run != null;
        }
    }

    private record ScanExecution(
            Map<String, Object> response,
            int candidateCount,
            int createdCount) {}

    @FunctionalInterface
    private interface TransactionWork<T> {
        T execute();
    }
}
