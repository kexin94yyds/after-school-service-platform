package com.afterschool.platform.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class SupervisionServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 31, 18, 0);

    private SupervisionMapper mapper;
    private CurrentUser currentUser;
    private PlatformPrincipal principal;
    private SupervisionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SupervisionMapper.class);
        currentUser = mock(CurrentUser.class);
        principal = mock(PlatformPrincipal.class);
        when(currentUser.principal()).thenReturn(principal);
        service = new SupervisionService(
                mapper,
                currentUser,
                Clock.fixed(
                        Instant.parse("2026-07-31T10:00:00Z"),
                        ZoneId.of("Asia/Shanghai")),
                immediateTransactions(),
                Duration.ofHours(2));
    }

    @Test
    void regulatorScanCreatesOnlyNewDeduplicatedAlertsAndSuccessRunHistory() {
        when(principal.roleCode()).thenReturn("REGULATOR");
        when(principal.id()).thenReturn(9L);
        when(currentUser.optionalSchoolScope(1L)).thenReturn(1L);
        when(mapper.countRunningScanRuns()).thenReturn(0);
        when(mapper.insertScanRun(
                anyString(), eq("MANUAL"), eq(NOW), eq(9L)))
                .thenReturn(1);
        when(mapper.completeScanRun(
                anyString(), eq(NOW), eq(2), eq(1))).thenReturn(1);
        AlertDraft created = draft("OVERDUE_ATTENDANCE", "SESSION:10");
        AlertDraft duplicate = draft("LOW_ATTENDANCE", "OFFERING:11");
        when(mapper.findOverdueAttendanceCandidates(1L, 2L, NOW))
                .thenReturn(List.of(created));
        when(mapper.findOfferingsWithoutSessions(1L, 2L, NOW))
                .thenReturn(List.of());
        when(mapper.findLowAttendanceCandidates(
                        1L, 2L, new BigDecimal("0.7500")))
                .thenReturn(List.of(duplicate));
        AtomicReference<String> scanRunId = new AtomicReference<>();
        when(mapper.insertAlert(
                        eq(created),
                        eq(LocalDateTime.of(2026, 8, 5, 18, 0)),
                        anyString()))
                .thenAnswer(invocation -> {
                    scanRunId.set(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.insertAlert(
                        eq(duplicate),
                        eq(LocalDateTime.of(2026, 8, 5, 18, 0)),
                        anyString()))
                .thenReturn(0);
        when(mapper.findAlertIdentityByDedup(
                        1, "OVERDUE_ATTENDANCE", "SESSION:10"))
                .thenAnswer(invocation -> identity(100, scanRunId.get()));
        when(mapper.findAlertIdentityByDedup(
                        1, "LOW_ATTENDANCE", "OFFERING:11"))
                .thenReturn(identity(101, "different-scan"));

        Map<String, Object> result =
                service.scan(1L, 2L, new BigDecimal("0.7500"), 5);

        assertThat(result)
                .containsEntry("triggerSource", "MANUAL")
                .containsEntry("candidateCount", 2)
                .containsEntry("createdCount", 1)
                .containsEntry("deduplicatedCount", 1);
        assertThat(result.get("scanRunId")).isEqualTo(scanRunId.get());
        verify(mapper).markStaleScanRunsFailed(
                eq(NOW.minusHours(2)),
                eq(NOW),
                eq("扫描运行超时，已由后续扫描标记失败"));
        verify(mapper).insertAction(
                1,
                100,
                null,
                "OPEN",
                "CREATE",
                "监管扫描自动生成",
                9L,
                "REGULATOR");
    }

    @Test
    void scheduledScanUsesSystemActorAndDoesNotReadCurrentUser() {
        reset(currentUser);
        when(mapper.countRunningScanRuns()).thenReturn(0);
        when(mapper.insertScanRun(
                anyString(), eq("SCHEDULED"), eq(NOW), isNull()))
                .thenReturn(1);
        when(mapper.completeScanRun(
                anyString(), eq(NOW), eq(1), eq(1))).thenReturn(1);
        AlertDraft created = draft("OVERDUE_ATTENDANCE", "SESSION:10");
        AtomicReference<String> scanRunId = new AtomicReference<>();
        when(mapper.findOverdueAttendanceCandidates(null, null, NOW))
                .thenReturn(List.of(created));
        when(mapper.findOfferingsWithoutSessions(null, null, NOW))
                .thenReturn(List.of());
        when(mapper.findLowAttendanceCandidates(
                        null, null, new BigDecimal("0.8000")))
                .thenReturn(List.of());
        when(mapper.insertAlert(
                        eq(created),
                        eq(LocalDateTime.of(2026, 8, 7, 18, 0)),
                        anyString()))
                .thenAnswer(invocation -> {
                    scanRunId.set(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.findAlertIdentityByDedup(
                        1, "OVERDUE_ATTENDANCE", "SESSION:10"))
                .thenAnswer(invocation -> identity(100, scanRunId.get()));

        service.scanScheduled();

        verifyNoInteractions(currentUser);
        verify(mapper).insertSystemAction(
                1,
                100,
                null,
                "OPEN",
                "CREATE",
                "定时监管扫描自动生成");
    }

    @Test
    void scheduledFailureLeavesFailedRunRecordAndDoesNotPropagate() {
        reset(currentUser);
        when(mapper.countRunningScanRuns()).thenReturn(0);
        when(mapper.insertScanRun(
                anyString(), eq("SCHEDULED"), eq(NOW), isNull()))
                .thenReturn(1);
        when(mapper.findOverdueAttendanceCandidates(null, null, NOW))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(mapper.failScanRun(
                anyString(), eq(NOW), eq("扫描未完成，系统异常")))
                .thenReturn(1);

        service.scanScheduled();

        verifyNoInteractions(currentUser);
        verify(mapper).failScanRun(
                anyString(), eq(NOW), eq("扫描未完成，系统异常"));
    }

    @Test
    void activeDatabaseRunRejectsDuplicateScanAndLeavesARejectedTrace() {
        when(principal.roleCode()).thenReturn("REGULATOR");
        when(principal.id()).thenReturn(9L);
        when(currentUser.optionalSchoolScope(1L)).thenReturn(1L);
        when(mapper.countRunningScanRuns()).thenReturn(1);
        when(mapper.insertFailedScanRun(
                anyString(),
                eq("MANUAL"),
                eq(NOW),
                eq(NOW),
                eq("已有扫描正在执行，已拒绝重复启动"),
                eq(9L))).thenReturn(1);

        assertThatThrownBy(() -> service.scan(1L, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("SCAN_ALREADY_RUNNING");

        verify(mapper).insertFailedScanRun(
                anyString(),
                eq("MANUAL"),
                eq(NOW),
                eq(NOW),
                eq("已有扫描正在执行，已拒绝重复启动"),
                eq(9L));
        verify(mapper, never()).findOverdueAttendanceCandidates(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void localReentryIsRejectedBeforeItCanStartAnotherCandidateScan() {
        reset(currentUser);
        when(mapper.countRunningScanRuns()).thenReturn(0);
        when(mapper.insertScanRun(
                anyString(), eq("SCHEDULED"), eq(NOW), isNull()))
                .thenAnswer(invocation -> {
                    service.scanScheduled();
                    return 1;
                });
        when(mapper.insertFailedScanRun(
                anyString(),
                eq("SCHEDULED"),
                eq(NOW),
                eq(NOW),
                eq("已有扫描正在执行，已拒绝重复启动"),
                isNull())).thenReturn(1);
        when(mapper.findOverdueAttendanceCandidates(null, null, NOW))
                .thenReturn(List.of());
        when(mapper.findOfferingsWithoutSessions(null, null, NOW))
                .thenReturn(List.of());
        when(mapper.findLowAttendanceCandidates(
                        null, null, new BigDecimal("0.8000")))
                .thenReturn(List.of());
        when(mapper.completeScanRun(
                anyString(), eq(NOW), eq(0), eq(0))).thenReturn(1);

        service.scanScheduled();

        verifyNoInteractions(currentUser);
        verify(mapper, times(1)).insertScanRun(
                anyString(), eq("SCHEDULED"), eq(NOW), isNull());
        verify(mapper).insertFailedScanRun(
                anyString(),
                eq("SCHEDULED"),
                eq(NOW),
                eq(NOW),
                eq("已有扫描正在执行，已拒绝重复启动"),
                isNull());
    }

    @Test
    void scanRunHistoryIsRegulatorOnlyAndUsesBoundedDefaultLimit() {
        when(principal.roleCode()).thenReturn("REGULATOR");
        when(mapper.listScanRuns(50)).thenReturn(List.of(Map.of("id", "run-1")));

        assertThat(service.listScanRuns(null))
                .containsExactly(Map.of("id", "run-1"));
        verify(mapper).listScanRuns(50);

        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        assertThatThrownBy(() -> service.listScanRuns(10))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void schoolCanOnlyAdvanceItsAlertThroughTheRectificationSequence() {
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        when(principal.schoolId()).thenReturn(3L);
        when(principal.id()).thenReturn(12L);
        SupervisionAlert alert = new SupervisionAlert();
        alert.setId(8);
        alert.setSchoolId(3);
        alert.setStatus("OPEN");
        when(mapper.lockAlert(8, 3L)).thenReturn(alert);
        when(mapper.updateStatus(8, "OPEN", "ACKNOWLEDGED"))
                .thenReturn(1);
        when(mapper.listAlerts(3L, null, null, null, null))
                .thenReturn(List.of(Map.of(
                        "id", 8L,
                        "status", "ACKNOWLEDGED")));

        Map<String, Object> result = service.transition(
                8, "acknowledged", "学校已接收并安排负责人");

        assertThat(result).containsEntry("status", "ACKNOWLEDGED");
        verify(mapper).insertAction(
                3,
                8,
                "OPEN",
                "ACKNOWLEDGED",
                "ACKNOWLEDGE",
                "学校已接收并安排负责人",
                12L,
                "SCHOOL_ADMIN");
    }

    @Test
    void regulatorCannotCloseAnAlertBeforeSchoolSubmitsVerification() {
        when(principal.roleCode()).thenReturn("REGULATOR");
        SupervisionAlert alert = new SupervisionAlert();
        alert.setId(8);
        alert.setSchoolId(3);
        alert.setStatus("OPEN");
        when(mapper.lockAlert(8, null)).thenReturn(alert);

        assertThatThrownBy(
                        () -> service.transition(8, "CLOSED", "直接关闭"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INVALID_ALERT_TRANSITION");
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return callback.doInTransaction(null);
            }
        };
    }

    private AlertIdentity identity(long id, String scanRunId) {
        AlertIdentity identity = new AlertIdentity();
        identity.setId(id);
        identity.setScanRunId(scanRunId);
        return identity;
    }

    private AlertDraft draft(String type, String key) {
        AlertDraft draft = new AlertDraft();
        draft.setSchoolId(1);
        draft.setOfferingId(11);
        draft.setAlertType(type);
        draft.setDedupKey(key);
        draft.setSeverity("HIGH");
        draft.setTitle("测试");
        draft.setDescription("测试预警");
        return draft;
    }
}
