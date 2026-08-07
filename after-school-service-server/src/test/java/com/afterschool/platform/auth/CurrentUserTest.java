package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterschool.platform.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void schoolAdminCannotSelectAnotherSchool() {
        authenticate("SCHOOL_ADMIN", 1L, null, null);

        assertThatThrownBy(() -> currentUser.optionalSchoolScope(2L))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void schoolAdminAlwaysReceivesOwnScope() {
        authenticate("SCHOOL_ADMIN", 7L, null, null);
        assertThat(currentUser.optionalSchoolScope(null)).isEqualTo(7L);
        assertThat(currentUser.schoolScope(null)).isEqualTo(7L);
    }

    @Test
    void regulatorMayReadAllButMustSpecifySchoolForWrite() {
        authenticate("REGULATOR", null, null, null);
        assertThat(currentUser.optionalSchoolScope(null)).isNull();
        assertThatThrownBy(() -> currentUser.schoolScope(null))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("SCHOOL_REQUIRED");
    }

    @Test
    void malformedSchoolAccountCannotFallThroughToGlobalScope() {
        authenticate("SCHOOL_ADMIN", null, null, null);

        assertThatThrownBy(() -> currentUser.optionalSchoolScope(null))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("FORBIDDEN");
    }

    private void authenticate(String role, Long schoolId, Long teacherId, Long guardianId) {
        UserAccount account = new UserAccount();
        account.setId(1);
        account.setUsername("test");
        account.setPasswordHash("{noop}test");
        account.setDisplayName("测试账号");
        account.setEnabled(true);
        account.setRoleCode(role);
        account.setSchoolId(schoolId);
        account.setTeacherId(teacherId);
        account.setGuardianId(guardianId);
        PlatformPrincipal principal = new PlatformPrincipal(account);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, principal.getPassword(), principal.getAuthorities()));
    }
}
