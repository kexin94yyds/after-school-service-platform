package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialSchoolAdminBootstrapTest {

    private BootstrapAdminMapper mapper;
    private PasswordEncoder passwordEncoder;
    private InitialSchoolAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        mapper = mock(BootstrapAdminMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        bootstrap = new InitialSchoolAdminBootstrap(
                mapper,
                passwordEncoder,
                "initial_admin",
                "InitialAdmin@2026",
                "初始教务管理员",
                "SCHOOL-001",
                "初始化学校",
                "DISTRICT-001");
    }

    @Test
    void createsTheFirstSchoolAndActiveSchoolAdminTogether() {
        when(mapper.insertSchool("SCHOOL-001", "初始化学校", "DISTRICT-001"))
                .thenReturn(1);
        when(mapper.findSchoolIdByCode("SCHOOL-001")).thenReturn(7L);
        when(passwordEncoder.encode("InitialAdmin@2026")).thenReturn("{bcrypt}hash");
        when(mapper.insertSchoolAdmin(
                        7, "initial_admin", "{bcrypt}hash", "初始教务管理员"))
                .thenReturn(1);

        bootstrap.run(null);

        verify(mapper).insertSchool("SCHOOL-001", "初始化学校", "DISTRICT-001");
        verify(mapper).insertSchoolAdmin(
                7, "initial_admin", "{bcrypt}hash", "初始教务管理员");
    }

    @Test
    void becomesANoOpAfterAnySchoolAdminExists() {
        when(mapper.countSchoolAdmins()).thenReturn(1);

        bootstrap.run(null);

        verify(mapper, never()).insertSchool(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refusesToClaimAnExistingSchoolWithoutAnAdmin() {
        when(mapper.countSchools()).thenReturn(1);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止自动初始化");
        verify(mapper, never()).insertSchool(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
