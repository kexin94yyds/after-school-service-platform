package com.afterschool.platform.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class EvaluationServiceTest {

    private EvaluationMapper mapper;
    private CurrentUser currentUser;
    private PlatformPrincipal principal;
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EvaluationMapper.class);
        currentUser = mock(CurrentUser.class);
        principal = mock(PlatformPrincipal.class);
        when(currentUser.principal()).thenReturn(principal);
        service = new EvaluationService(mapper, currentUser);
    }

    @Test
    void guardianCanEvaluateAnEligibleCompletedOfferingOnlyOnce() {
        when(principal.roleCode()).thenReturn("GUARDIAN");
        when(principal.guardianId()).thenReturn(31L);
        when(principal.schoolId()).thenReturn(2L);
        when(mapper.lockStudent(10, 2)).thenReturn(10L);
        when(mapper.lockOffering(20, 2)).thenReturn(20L);
        EvaluationEligibility eligibility = new EvaluationEligibility();
        eligibility.setEnrollmentId(77);
        eligibility.setSchoolId(2);
        when(mapper.lockEligibility(10, 20, 31, 2))
                .thenReturn(eligibility);
        when(mapper.countEvaluation(20, 10)).thenReturn(0);
        when(mapper.insertEvaluation(
                        2, 20, 77, 10, 31, 5, 5, "孩子很喜欢"))
                .thenReturn(1);
        when(mapper.findEvaluation(20, 10))
                .thenReturn(Map.of("id", 90L, "rating", 5));

        Map<String, Object> created = service.submit(
                10, 20, 5, "  孩子很喜欢  ");

        assertThat(created)
                .containsEntry("id", 90L)
                .containsEntry("rating", 5);
        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockOffering(20, 2);
        locks.verify(mapper).lockStudent(10, 2);
        locks.verify(mapper).lockEligibility(10, 20, 31, 2);
        verify(mapper).insertEvaluation(
                2, 20, 77, 10, 31, 5, 5, "孩子很喜欢");
    }

    @Test
    void rejectsEvaluationWithoutBindingEnrollmentAndCompletedSession() {
        when(principal.roleCode()).thenReturn("GUARDIAN");
        when(principal.guardianId()).thenReturn(31L);
        when(principal.schoolId()).thenReturn(2L);
        when(mapper.lockStudent(10, 2)).thenReturn(10L);
        when(mapper.lockOffering(20, 2)).thenReturn(20L);
        when(mapper.lockEligibility(10, 20, 31, 2)).thenReturn(null);

        assertThatThrownBy(() -> service.submit(10, 20, 4, null))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("EVALUATION_NOT_ELIGIBLE");
    }

    @Test
    void guardianMineListingIsBoundToAuthenticatedGuardianAndSchool() {
        when(principal.roleCode()).thenReturn("GUARDIAN");
        when(principal.guardianId()).thenReturn(31L);
        when(principal.schoolId()).thenReturn(2L);
        when(mapper.listGuardianEvaluations(31, 2)).thenReturn(List.of(
                Map.of(
                        "id", 90L,
                        "studentId", 10L,
                        "offeringId", 20L,
                        "rating", 5)));

        List<Map<String, Object>> result = service.mine();

        assertThat(result).hasSize(1);
        verify(mapper).listGuardianEvaluations(31, 2);
    }

    @Test
    void nonGuardianCannotReadGuardianEvaluationKeys() {
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");

        assertThatThrownBy(service::mine)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void schoolEvaluationListingIsForcedIntoItsOwnScope() {
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        when(currentUser.optionalSchoolScope(null)).thenReturn(5L);

        service.list(null, null, null, null, null, null, null);

        verify(mapper).listEvaluations(
                5L, null, null, null, null, null, null);
    }
}
