package com.afterschool.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ActiveAccountFilter extends OncePerRequestFilter {

    private final UserAccountMapper mapper;

    public ActiveAccountFilter(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof PlatformPrincipal principal) {
            UserAccount current = mapper.findActiveById(principal.id());
            boolean unchanged = current != null
                    && Objects.equals(current.getUsername(), principal.getUsername())
                    && Objects.equals(current.getPasswordHash(), principal.getPassword())
                    && Objects.equals(current.getSchoolId(), principal.schoolId())
                    && Objects.equals(current.getRoleCode(), principal.roleCode())
                    && Objects.equals(current.getTeacherId(), principal.teacherId())
                    && Objects.equals(current.getGuardianId(), principal.guardianId());
            if (unchanged) {
                filterChain.doFilter(request, response);
                return;
            }
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"ACCOUNT_CHANGED\",\"message\":\"账号状态或凭据已变更，请重新登录\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
