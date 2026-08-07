package com.afterschool.platform.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupervisionServiceTest {

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
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void regulatorScanCreatesOnlyNewDeduplicatedAlertsAndHistory() {
        when(principal.roleCode()).thenReturn("REGULATOR");
        when(principal.id()).thenReturn(9L);
        when(currentUser.optionalSchoolScope(1L)).thenReturn(1L);
        AlertDraft created = draft("OVERDUE_ATTENDANCE", "SESSION:10");
        AlertDraft duplicate = draft("LOW_ATTENDANCE", "OFFERING:11");
        when(mapper.findOverdueAttendanceCandidates(
                        1L,
                        2L,
                        java.time.LocalDateTime.of(2026, 7, 31, 18, 0)))
                .thenReturn(List.of(created));
        when(mapper.findOfferingsWithoutSessions(
                        1L,
                        2L,
                        java.time.LocalDateTime.of(2026, 7, 31, 18, 0)))
                .thenReturn(List.of());
        when(mapper.findLowAttendanceCandidates(
                        1L, 2L, new BigDecimal("0.7500")))
                .thenReturn(List.of(duplicate));
        AtomicReference<String> scanRunId = new AtomicReference<>();
        when(mapper.insertAlert(
                        eq(created),
                        eq(java.time.LocalDateTime.of(
                                2026, 8, 5, 18, 0)),
                        anyString()))
                .thenAnswer(invocation -> {
                    scanRunId.set(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.insertAlert(
                        eq(duplicate),
                        eq(java.time.LocalDateTime.of(
                                2026, 8, 5, 18, 0)),
                        anyString()))
                .thenReturn(0);
        when(mapper.findAlertIdentityByDedup(
                        1, "OVERDUE_ATTENDANCE", "SESSION:10"))
                .thenAnswer(invocation -> {
                    AlertIdentity identity = new AlertIdentity();
                    identity.setId(100);
                    identity.setScanRunId(scanRunId.get());
                    return identity;
                });
        when(mapper.findAlertIdentityByDedup(
                        1, "LOW_ATTENDANCE", "OFFERING:11"))
                .thenAnswer(invocation -> {
                    AlertIdentity identity = new AlertIdentity();
                    identity.setId(101);
                    identity.setScanRunId("different-scan");
                    return identity;
                });

        Map<String, Object> result =
                service.scan(1L, 2L, new BigDecimal("0.7500"), 5);

        assertThat(result)
                .containsEntry("candidateCount", 2)
                .containsEntry("createdCount", 1)
                .containsEntry("deduplicatedCount", 1);
        verify(mapper).insertAction(
                1,
                100,
                null,
                "OPEN",
                "CREATE",
                "监管扫描自动生成",
                9,
                "REGULATOR");
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
                12,
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
