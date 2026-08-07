package com.afterschool.platform.audit;

import com.afterschool.platform.auth.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class OperationAuditFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(OperationAuditFilter.class);
    private static final Set<String> MUTATING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private final OperationAuditService service;
    private final TransactionTemplate transactionTemplate;

    public OperationAuditFilter(
            OperationAuditService service,
            PlatformTransactionManager transactionManager) {
        this.service = service;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || !MUTATING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        PlatformPrincipal before = authenticatedPrincipal();
        ContentCachingResponseWrapper buffered =
                new ContentCachingResponseWrapper(response);
        try {
            transactionTemplate.executeWithoutResult(transaction -> {
                try {
                    filterChain.doFilter(request, buffered);
                } catch (ServletException | IOException exception) {
                    transaction.setRollbackOnly();
                    throw new FilterChainFailure(exception);
                }
                if (buffered.getStatus() < 200
                        || buffered.getStatus() > 299) {
                    transaction.setRollbackOnly();
                    return;
                }
                PlatformPrincipal actor =
                        before == null
                                ? authenticatedPrincipal()
                                : before;
                if (actor == null) {
                    transaction.setRollbackOnly();
                    return;
                }
                Long targetSchoolId =
                        AuditTargetContext.targetSchoolId();
                if (targetSchoolId == null) {
                    targetSchoolId = actor.schoolId();
                }
                service.record(new AuditEvent(
                        actor.id(),
                        actor.roleCode(),
                        actor.schoolId(),
                        targetSchoolId,
                        request.getMethod(),
                        safePath(request.getRequestURI()),
                        buffered.getStatus(),
                        sourceFingerprint(request)));
            });
            buffered.copyBodyToResponse();
        } catch (FilterChainFailure failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ServletException servletException) {
                throw servletException;
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw failure;
        } catch (RuntimeException exception) {
            log.error(
                    "Mutating operation was rolled back because its audit record could not be committed: {} {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception);
            if (response.isCommitted()) {
                throw exception;
            }
            response.reset();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"AUDIT_PERSISTENCE_FAILED\","
                    + "\"message\":\"操作未提交，请稍后重试\"}");
        }
    }

    private PlatformPrincipal authenticatedPrincipal() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                    instanceof PlatformPrincipal principal) {
            return principal;
        }
        return null;
    }

    static String sourceFingerprint(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        String source = request.getRemoteAddr()
                + "\n"
                + (userAgent == null ? "" : userAgent);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime",
                    exception);
        }
    }

    private String safePath(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static final class FilterChainFailure
            extends RuntimeException {

        private FilterChainFailure(Exception cause) {
            super(cause);
        }
    }
}
