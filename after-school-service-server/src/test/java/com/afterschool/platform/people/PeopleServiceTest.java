package com.afterschool.platform.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class PeopleServiceTest {

    private PeopleMapper mapper;
    private CurrentUser currentUser;
    private PasswordEncoder passwordEncoder;
    private PeopleService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PeopleMapper.class);
        currentUser = mock(CurrentUser.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new PeopleService(mapper, currentUser, passwordEncoder);
        when(currentUser.schoolScope(1L)).thenReturn(1L);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}encoded");
    }

    @Test
    void inactiveTeacherIsCreatedWithDisabledLoginAndProfile() {
        doAnswer(invocation -> {
            ((NewUser) invocation.getArgument(0)).setId(11L);
            return 1;
        }).when(mapper).insertUser(any(NewUser.class));
        when(mapper.findTeacherByNo(1, "T-001"))
                .thenReturn(Map.of("id", 9L));

        service.createTeacher(new PeopleController.TeacherRequest(
                1L,
                "T-001",
                "停用教师",
                "inactive_teacher",
                "TeacherPassword@2026",
                null,
                null,
                "INACTIVE"));

        ArgumentCaptor<NewUser> user = ArgumentCaptor.forClass(NewUser.class);
        verify(mapper).insertUser(user.capture());
        assertThat(user.getValue().isEnabled()).isFalse();
        verify(mapper).insertTeacher(
                1,
                11,
                "T-001",
                "停用教师",
                null,
                null,
                "INACTIVE");
    }

    @Test
    void inactiveStudentKeepsRequestedStatus() {
        when(mapper.classExists(2, 1)).thenReturn(1);
        when(mapper.findStudentByNo(1, "S-001"))
                .thenReturn(Map.of("id", 8L));

        service.createStudent(new PeopleController.StudentRequest(
                1L,
                2,
                "S-001",
                "停用学生",
                "FEMALE",
                LocalDate.of(2017, 1, 1),
                "INACTIVE"));

        verify(mapper).insertStudent(
                1,
                2,
                "S-001",
                "停用学生",
                "FEMALE",
                LocalDate.of(2017, 1, 1),
                "INACTIVE");
    }

    @Test
    void inactiveGuardianIsCreatedWithDisabledLoginAndProfile() {
        when(mapper.countStudentsInSchool(1, List.of(4L))).thenReturn(1);
        doAnswer(invocation -> {
            ((NewUser) invocation.getArgument(0)).setId(12L);
            return 1;
        }).when(mapper).insertUser(any(NewUser.class));
        doAnswer(invocation -> {
            ((NewGuardian) invocation.getArgument(0)).setId(13L);
            return 1;
        }).when(mapper).insertGuardian(any(NewGuardian.class));
        when(mapper.findGuardianByUsername(1, "inactive_guardian"))
                .thenReturn(Map.of("id", 13L, "studentIds", "4"));

        service.createGuardian(new PeopleController.GuardianRequest(
                1L,
                "停用家长",
                "13800000001",
                "inactive_guardian",
                "GuardianPassword@2026",
                List.of(4L),
                "母亲",
                true,
                "INACTIVE"));

        ArgumentCaptor<NewUser> user = ArgumentCaptor.forClass(NewUser.class);
        ArgumentCaptor<NewGuardian> guardian = ArgumentCaptor.forClass(NewGuardian.class);
        verify(mapper).insertUser(user.capture());
        verify(mapper).insertGuardian(guardian.capture());
        assertThat(user.getValue().isEnabled()).isFalse();
        assertThat(guardian.getValue().getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void rejectsTeacherInitialPasswordShorterThanTwelveCharacters() {
        assertThatThrownBy(() -> service.createTeacher(
                        new PeopleController.TeacherRequest(
                                1L,
                                "T-002",
                                "教师",
                                "short_teacher",
                                "12345678901",
                                null,
                                null,
                                "ACTIVE")))
                .hasMessageContaining("12");
    }

    @Test
    void rejectsGuardianInitialPasswordShorterThanTwelveCharacters() {
        assertThatThrownBy(() -> service.createGuardian(
                        new PeopleController.GuardianRequest(
                                1L,
                                "家长",
                                "13800000002",
                                "short_guardian",
                                "12345678901",
                                List.of(4L),
                                "父亲",
                                true,
                                "ACTIVE")))
                .hasMessageContaining("12");
    }

    @Test
    void guardianBindingCanBeRevokedEvenWhenHistoricEnrollmentExists() {
        when(mapper.countStudentsInSchool(1, List.of(5L))).thenReturn(1);
        when(mapper.guardianUserId(13, 1)).thenReturn(12L);
        when(mapper.listGuardians(1L)).thenReturn(List.of(Map.of(
                "id", 13L,
                "studentIds", "5")));

        PeopleController.GuardianRequest request =
                new PeopleController.GuardianRequest(
                        1L,
                        "家长",
                        "13800000001",
                        "guardian",
                        null,
                        List.of(5L),
                        "母亲",
                        true,
                        "ACTIVE");

        assertThatCode(() -> service.updateGuardian(13, request))
                .doesNotThrowAnyException();
        verify(mapper).insertGuardianBinding(5, 13, "母亲", true);
        verify(mapper).deactivateGuardianBindingsExcept(13, List.of(5L));
    }
}
