package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthControllerTest {

    private UserAccountMapper mapper;
    private PasswordEncoder passwordEncoder;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(UserAccountMapper.class);
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        controller = new AuthController(
                mock(AuthenticationManager.class),
                mapper,
                passwordEncoder,
                mock(LoginAttemptGuard.class));
        authenticate("CurrentPassword@2026");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changesPasswordAndEndsCurrentSession() {
        String expectedPasswordHash = currentPrincipal().getPassword();
        when(mapper.updatePassword(
                        eq(7L),
                        eq(expectedPasswordHash),
                        anyString()))
                .thenReturn(1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);

        controller.changePassword(
                new AuthController.ChangePasswordRequest(
                        "CurrentPassword@2026",
                        "NewPassword@2026"),
                request);

        verify(mapper).updatePassword(
                eq(7L),
                eq(expectedPasswordHash),
                anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void rejectsPasswordChangeWhenStoredPasswordChangedConcurrently() {
        String expectedPasswordHash = currentPrincipal().getPassword();
        when(mapper.updatePassword(
                        eq(7L),
                        eq(expectedPasswordHash),
                        anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest(
                                "CurrentPassword@2026",
                                "NewPassword@2026"),
                        new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("ACCOUNT_CHANGED");

        verify(mapper).updatePassword(
                eq(7L),
                eq(expectedPasswordHash),
                anyString());
    }

    @Test
    void rejectsIncorrectCurrentPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest(
                                "WrongPassword@2026",
                                "NewPassword@2026"),
                        request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CURRENT_PASSWORD_INVALID");
    }

    @Test
    void rejectsValidCredentialsAtTheWrongRolePortal() {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        PlatformPrincipal principal = currentPrincipal();
        when(manager.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        principal.getPassword(),
                        principal.getAuthorities()));
        AuthController roleController = new AuthController(
                manager,
                mapper,
                passwordEncoder,
                mock(LoginAttemptGuard.class));
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> roleController.login(
                        new AuthController.LoginRequest(
                                "operator", "CurrentPassword@2026", "STUDENT"),
                        request,
                        new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("ROLE_LOGIN_MISMATCH");

        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void authenticate(String password) {
        UserAccount account = new UserAccount();
        account.setId(7);
        account.setUsername("operator");
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setDisplayName("操作员");
        account.setEnabled(true);
        account.setRoleCode("REGULATOR");
        PlatformPrincipal principal = new PlatformPrincipal(account);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        principal.getPassword(),
                        principal.getAuthorities()));
    }

    private PlatformPrincipal currentPrincipal() {
        return (PlatformPrincipal) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}
