package com.afterschool.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptGuard loginAttemptGuard;
    private final HttpSessionSecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(
            AuthenticationManager authenticationManager,
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            LoginAttemptGuard loginAttemptGuard) {
        this.authenticationManager = authenticationManager;
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    @PostMapping("/login")
    SessionUser login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = body.username().strip();
        String remoteAddress = request.getRemoteAddr();
        loginAttemptGuard.checkAllowed(username, remoteAddress);

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            username, body.password()));
        } catch (org.springframework.security.core.AuthenticationException exception) {
            loginAttemptGuard.recordFailure(username, remoteAddress);
            throw exception;
        }
        loginAttemptGuard.recordSuccess(username, remoteAddress);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        userAccountMapper.updateLastLogin(principal.id());
        return SessionUser.from(principal);
    }

    @GetMapping("/me")
    SessionUser me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return SessionUser.from((PlatformPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        if (!passwordEncoder.matches(body.currentPassword(), principal.getPassword())) {
            throw com.afterschool.platform.common.ApiException.badRequest(
                    "CURRENT_PASSWORD_INVALID",
                    "当前密码不正确");
        }
        if (passwordEncoder.matches(body.newPassword(), principal.getPassword())) {
            throw com.afterschool.platform.common.ApiException.badRequest(
                    "PASSWORD_UNCHANGED",
                    "新密码不能与当前密码相同");
        }
        if (userAccountMapper.updatePassword(
                        principal.id(),
                        principal.getPassword(),
                        passwordEncoder.encode(body.newPassword()))
                != 1) {
            throw com.afterschool.platform.common.ApiException.conflict(
                    "ACCOUNT_CHANGED",
                    "账号状态已变化，请重新登录");
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public record LoginRequest(
            @NotBlank(message = "请输入用户名") String username,
            @NotBlank(message = "请输入密码") @Size(max = 72) String password) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "请输入当前密码") @Size(max = 72) String currentPassword,
            @NotBlank(message = "请输入新密码") @Size(min = 12, max = 72) String newPassword) {}

    public record SessionUser(
            long id,
            Long schoolId,
            String username,
            String displayName,
            String role,
            Long teacherId,
            Long guardianId,
            Map<String, Boolean> capabilities) {

        static SessionUser from(PlatformPrincipal principal) {
            String role = principal.roleCode();
            return new SessionUser(
                    principal.id(),
                    principal.schoolId(),
                    principal.getUsername(),
                    principal.displayName(),
                    role,
                    principal.teacherId(),
                    principal.guardianId(),
                    Map.of(
                            "manageSchools", "REGULATOR".equals(role),
                            "manageSchoolData", "SCHOOL_ADMIN".equals(role),
                            "recordAttendance", "TEACHER".equals(role) || "SCHOOL_ADMIN".equals(role),
                            "enrollChildren", "GUARDIAN".equals(role),
                            "viewReports", "REGULATOR".equals(role) || "SCHOOL_ADMIN".equals(role)));
        }
    }
}
