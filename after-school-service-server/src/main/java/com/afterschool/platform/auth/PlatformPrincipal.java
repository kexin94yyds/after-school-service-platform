package com.afterschool.platform.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class PlatformPrincipal implements UserDetails {

    private final long id;
    private final Long schoolId;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final boolean enabled;
    private final String roleCode;
    private final Long studentId;
    private final Long teacherId;
    private final Long guardianId;

    public PlatformPrincipal(UserAccount account) {
        this.id = account.getId();
        this.schoolId = account.getSchoolId();
        this.username = account.getUsername();
        this.passwordHash = account.getPasswordHash();
        this.displayName = account.getDisplayName();
        this.enabled = account.isEnabled();
        this.roleCode = account.getRoleCode();
        this.studentId = account.getStudentId();
        this.teacherId = account.getTeacherId();
        this.guardianId = account.getGuardianId();
    }

    public long id() {
        return id;
    }

    public Long schoolId() {
        return schoolId;
    }

    public String displayName() {
        return displayName;
    }

    public String roleCode() {
        return roleCode;
    }

    public Long teacherId() {
        return teacherId;
    }

    public Long studentId() {
        return studentId;
    }

    public Long guardianId() {
        return guardianId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + roleCode));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
