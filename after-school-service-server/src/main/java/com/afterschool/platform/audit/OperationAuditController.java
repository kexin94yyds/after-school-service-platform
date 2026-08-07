package com.afterschool.platform.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
public class OperationAuditController {

    private final OperationAuditService service;

    public OperationAuditController(OperationAuditService service) {
        this.service = service;
    }

    @GetMapping
    List<Map<String, Object>> auditLogs(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String pathPrefix,
            @RequestParam(required = false) LocalDateTime occurredFrom,
            @RequestParam(required = false) LocalDateTime occurredTo) {
        return service.list(
                schoolId,
                actorUserId,
                method,
                pathPrefix,
                occurredFrom,
                occurredTo);
    }
}
