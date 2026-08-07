package com.afterschool.platform.supervision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/supervision/alerts")
@PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
public class SupervisionController {

    private final SupervisionService service;

    public SupervisionController(SupervisionService service) {
        this.service = service;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasRole('REGULATOR')")
    Map<String, Object> scan(
            @Valid @RequestBody(required = false) ScanRequest request) {
        ScanRequest effective = request == null
                ? new ScanRequest(null, null, null, null)
                : request;
        return service.scan(
                effective.schoolId(),
                effective.termId(),
                effective.lowAttendanceThreshold(),
                effective.deadlineDays());
    }

    @GetMapping
    List<Map<String, Object>> alerts(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDateTime detectedFrom,
            @RequestParam(required = false) LocalDateTime detectedTo) {
        return service.list(
                schoolId, type, status, detectedFrom, detectedTo);
    }

    @PostMapping("/{id}/transition")
    Map<String, Object> transition(
            @PathVariable long id,
            @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request.targetStatus(), request.comment());
    }

    @GetMapping("/{id}/history")
    List<Map<String, Object>> history(@PathVariable long id) {
        return service.history(id);
    }

    public record ScanRequest(
            @Min(1) Long schoolId,
            @Min(1) Long termId,
            @DecimalMin("0.01") @DecimalMax("1.00")
            BigDecimal lowAttendanceThreshold,
            @Min(1) @Max(30) Integer deadlineDays) {}

    public record TransitionRequest(
            @NotBlank @Size(max = 24) String targetStatus,
            @NotBlank @Size(max = 1000) String comment) {}
}
