package com.afterschool.platform.organization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.common.ApiException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class OrganizationServiceTest {

    private OrganizationMapper mapper;
    private PasswordEncoder passwordEncoder;
    private OrganizationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OrganizationMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new OrganizationService(
                mapper,
                mock(CurrentUser.class),
                passwordEncoder);
    }

    @Test
    void regulatorCanProvisionSchoolAdministrator() {
        when(mapper.schoolExists(3)).thenReturn(1);
        when(passwordEncoder.encode("InitialAdmin@2026")).thenReturn("{bcrypt}encoded");
        when(mapper.findSchoolAdminByUsername(3, "school_operator"))
                .thenReturn(Map.of("id", 20L, "username", "school_operator"));

        service.createSchoolAdmin(
                3,
                new OrganizationController.SchoolAdminRequest(
                        "school_operator",
                        "学校操作员",
                        "13800000000",
                        "InitialAdmin@2026",
                        true));

        verify(mapper).insertSchoolAdmin(
                3,
                "school_operator",
                "{bcrypt}encoded",
                "学校操作员",
                "13800000000",
                true);
    }

    @Test
    void administratorCreationRequiresPassword() {
        when(mapper.schoolExists(3)).thenReturn(1);

        assertThatThrownBy(() -> service.createSchoolAdmin(
                        3,
                        new OrganizationController.SchoolAdminRequest(
                                "school_operator",
                                "学校操作员",
                                null,
                                null,
                                true)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("PASSWORD_REQUIRED");
    }
}
