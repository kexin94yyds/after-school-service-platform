package com.afterschool.platform.auth;

import com.afterschool.platform.audit.AuditTargetContext;
import com.afterschool.platform.common.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public PlatformPrincipal principal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof PlatformPrincipal platformPrincipal) {
            return platformPrincipal;
        }
        throw ApiException.forbidden("当前登录状态无效");
    }

    public boolean hasRole(String role) {
        return role.equals(principal().roleCode());
    }

    public long schoolScope(Long requestedSchoolId) {
        PlatformPrincipal principal = principal();
        if ("REGULATOR".equals(principal.roleCode())) {
            if (requestedSchoolId == null) {
                throw ApiException.badRequest("SCHOOL_REQUIRED", "该操作需要指定学校");
            }
            AuditTargetContext.setTargetSchoolId(requestedSchoolId);
            return requestedSchoolId;
        }
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前账号没有学校数据权限");
        }
        if (requestedSchoolId != null && !principal.schoolId().equals(requestedSchoolId)) {
            throw ApiException.forbidden("不能访问其他学校的数据");
        }
        AuditTargetContext.setTargetSchoolId(principal.schoolId());
        return principal.schoolId();
    }

    public Long optionalSchoolScope(Long requestedSchoolId) {
        PlatformPrincipal principal = principal();
        if ("REGULATOR".equals(principal.roleCode())) {
            AuditTargetContext.setTargetSchoolId(requestedSchoolId);
            return requestedSchoolId;
        }
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前账号没有学校数据权限");
        }
        if (requestedSchoolId != null && !requestedSchoolId.equals(principal.schoolId())) {
            throw ApiException.forbidden("不能访问其他学校的数据");
        }
        AuditTargetContext.setTargetSchoolId(principal.schoolId());
        return principal.schoolId();
    }
}
