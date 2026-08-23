package com.afterschool.platform.teaching;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TeachingController {

    private final TeachingService service;

    public TeachingController(TeachingService service) {
        this.service = service;
    }

    @GetMapping("/offerings/{offeringId}/sessions")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> sessions(@PathVariable long offeringId) {
        return service.sessions(offeringId);
    }

    @PostMapping("/offerings/{offeringId}/sessions/generate")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    List<Map<String, Object>> generateSessions(@PathVariable long offeringId) {
        return service.generateSessions(offeringId);
    }

    @PutMapping("/sessions/{sessionId}")
    @PreAuthorize("hasRole('TEACHER')")
    Map<String, Object> updateSession(
            @PathVariable long sessionId, @Valid @RequestBody SessionRequest request) {
        return service.updateSession(sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/attendance")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> attendance(@PathVariable long sessionId) {
        return service.attendance(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/attendance")
    @PreAuthorize("hasRole('TEACHER')")
    List<Map<String, Object>> saveAttendance(
            @PathVariable long sessionId, @Valid @RequestBody AttendanceBatch request) {
        return service.saveAttendance(sessionId, request);
    }

    public record SessionRequest(@Size(max = 500) String notes) {}

    public record AttendanceBatch(@NotEmpty List<@Valid AttendanceRecord> records) {}

    public record AttendanceRecord(
            @Positive long studentId,
            @NotNull @Pattern(regexp = "PRESENT|LATE|LEAVE|ABSENT") String status,
            @Size(max = 255) String remark) {}
}
