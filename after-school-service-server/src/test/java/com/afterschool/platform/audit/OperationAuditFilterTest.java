package com.afterschool.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterschool.platform.auth.AuthController;
import com.afterschool.platform.auth.LoginAttemptGuard;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.auth.UserAccount;
import com.afterschool.platform.auth.UserAccountMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OperationAuditFilterTest {

    private final OperationAuditService service =
            mock(OperationAuditService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private OperationAuditFilter filter;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        filter = new OperationAuditFilter(service, transactionManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsMetadataOnlyForSuccessfulMutatingApiCalls()
            throws Exception {
        authenticate();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/change-password");
        request.setRemoteAddr("192.0.2.8");
        request.addHeader("User-Agent", "Browser/1.0");
        request.setContentType("application/json");
        request.setContent(
                "{\"currentPassword\":\"secret\",\"newPassword\":\"new-secret\"}"
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse)
                                .setStatus(204));

        ArgumentCaptor<AuditEvent> event =
                ArgumentCaptor.forClass(AuditEvent.class);
        verify(service).record(event.capture());
        assertThat(event.getValue().actorUserId()).isEqualTo(7);
        assertThat(event.getValue().actorSchoolId()).isEqualTo(3);
        assertThat(event.getValue().targetSchoolId()).isEqualTo(3);
        assertThat(event.getValue().requestPath())
                .isEqualTo("/api/auth/change-password");
        assertThat(event.getValue().responseStatus()).isEqualTo(204);
        assertThat(event.getValue().sourceFingerprint()).hasSize(64);
        assertThat(event.getValue().toString())
                .doesNotContain("secret", "currentPassword", "newPassword");
    }

    @Test
    void startsMutatingApiTransactionsAtReadCommitted()
            throws Exception {
        authenticate();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/sessions/100/reschedule");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse)
                                .setStatus(204));

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void doesNotRecordFailedMutatingCalls() throws Exception {
        authenticate();
        MockHttpServletRequest request =
                new MockHttpServletRequest("DELETE", "/api/enrollments/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse)
                                .setStatus(403));

        verify(service, never()).record(
                org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<TransactionStatus> transaction =
                ArgumentCaptor.forClass(TransactionStatus.class);
        verify(transactionManager).commit(transaction.capture());
        assertThat(transaction.getValue().isRollbackOnly()).isTrue();
    }

    @Test
    void auditFailureRollsBackAndReplacesSuccessWithServerError()
            throws Exception {
        authenticate();
        doThrow(new IllegalStateException("audit unavailable"))
                .when(service)
                .record(org.mockito.ArgumentMatchers.any());
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/courses");
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse)
                                .setStatus(201));

        verify(transactionManager).rollback(
                org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .contains("AUDIT_PERSISTENCE_FAILED")
                .doesNotContain("audit unavailable");
        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void loginAuditFailureInvalidatesTheNewSessionInTheMockMvcFilterChain()
            throws Exception {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);
        PlatformPrincipal principal = principal();
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        principal.getPassword(),
                        principal.getAuthorities()));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(service)
                .record(any());
        AuthController controller = new AuthController(
                authenticationManager,
                userAccountMapper,
                PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                mock(LoginAttemptGuard.class));
        AtomicReference<MockHttpSession> createdSession = new AtomicReference<>();
        Filter sessionCapture = (request, response, chain) -> {
            chain.doFilter(request, response);
            createdSession.set((MockHttpSession) ((HttpServletRequest) request)
                    .getSession(false));
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter, sessionCapture)
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"school-admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIT_PERSISTENCE_FAILED"));

        MockHttpSession session = createdSession.get();
        assertThat(session).isNotNull();
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void loginAuditFailureInvalidatesSessionEvenWhenTheResponseIsAlreadyCommitted() {
        authenticate();
        doThrow(new IllegalStateException("audit unavailable"))
                .when(service)
                .record(any());
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        assertThatThrownBy(() -> filter.doFilter(
                        request,
                        response,
                        (servletRequest, servletResponse) ->
                                ((HttpServletResponse) servletResponse)
                                        .setStatus(201)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void authenticate() {
        PlatformPrincipal principal = principal();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        principal.getPassword(),
                        principal.getAuthorities()));
    }

    private PlatformPrincipal principal() {
        UserAccount account = new UserAccount();
        account.setId(7);
        account.setSchoolId(3L);
        account.setUsername("school-admin");
        account.setPasswordHash("{noop}password");
        account.setDisplayName("校管理员");
        account.setEnabled(true);
        account.setRoleCode("SCHOOL_ADMIN");
        return new PlatformPrincipal(account);
    }
}
