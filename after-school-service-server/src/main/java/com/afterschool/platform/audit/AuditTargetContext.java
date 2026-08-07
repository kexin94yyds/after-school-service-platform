package com.afterschool.platform.audit;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Request-scoped target tenant metadata. Business scope resolution writes it;
 * the audit filter reads it only after a successful mutation.
 */
public final class AuditTargetContext {

    private static final String ATTRIBUTE =
            AuditTargetContext.class.getName() + ".targetSchoolId";

    private AuditTargetContext() {}

    public static void setTargetSchoolId(Long schoolId) {
        if (schoolId == null) {
            return;
        }
        RequestAttributes attributes =
                RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(
                    ATTRIBUTE,
                    schoolId,
                    RequestAttributes.SCOPE_REQUEST);
        }
    }

    public static Long targetSchoolId() {
        RequestAttributes attributes =
                RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(
                ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        return value instanceof Number number
                ? number.longValue()
                : null;
    }
}
