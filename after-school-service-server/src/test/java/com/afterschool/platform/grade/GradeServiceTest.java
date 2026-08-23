package com.afterschool.platform.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GradeServiceTest {

    private GradeMapper mapper;
    private CurrentUser currentUser;
    private GradeService service;
    private PlatformPrincipal teacher;

    @BeforeEach
    void setUp() {
        mapper = mock(GradeMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new GradeService(mapper, currentUser);
        teacher = mock(PlatformPrincipal.class);
        when(teacher.id()).thenReturn(12L);
        when(teacher.schoolId()).thenReturn(1L);
        when(teacher.teacherId()).thenReturn(2L);
        when(teacher.roleCode()).thenReturn("TEACHER");
        when(currentUser.principal()).thenReturn(teacher);
    }

    @Test
    void teacherUpdateAppendsRevisionBeforeChangingCurrentGrade() {
        GradeTarget target = target();
        GradeRecord current = new GradeRecord();
        current.setId(7);
        current.setSchoolId(1);
        current.setScore(new BigDecimal("88.00"));
        current.setLearningEvaluation("继续练习");
        when(mapper.lockTarget(20, 10)).thenReturn(target);
        when(mapper.lockGrade(20, 10)).thenReturn(current);
        when(mapper.insertRevision(
                        current,
                        new BigDecimal("92"),
                        "课堂参与积极",
                        12))
                .thenReturn(1);
        when(mapper.updateGrade(7, new BigDecimal("92"), "课堂参与积极", 12))
                .thenReturn(1);
        when(mapper.findGrade(20, 10)).thenReturn(Map.of("score", 92));

        Map<String, Object> result = service.save(new GradeController.GradeRequest(
                20, 10, new BigDecimal("92.00"), " 课堂参与积极 "));

        assertThat(result).containsEntry("score", 92);
        verify(mapper).insertRevision(
                current, new BigDecimal("92"), "课堂参与积极", 12);
        verify(mapper).updateGrade(7, new BigDecimal("92"), "课堂参与积极", 12);
    }

    @Test
    void teacherCannotGradeAnotherTeachersOffering() {
        GradeTarget target = target();
        target.setTeacherId(99);
        when(mapper.lockTarget(20, 10)).thenReturn(target);

        assertThatThrownBy(() -> service.save(new GradeController.GradeRequest(
                        20, 10, new BigDecimal("80"), "评价")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本人课程");

        verify(mapper, never()).insertGrade(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private GradeTarget target() {
        GradeTarget target = new GradeTarget();
        target.setSchoolId(1);
        target.setOfferingId(20);
        target.setStudentId(10);
        target.setTeacherId(2);
        target.setEnrollmentStatus("ENROLLED");
        return target;
    }
}
