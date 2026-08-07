package com.afterschool.platform.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AcademicController {

    private final AcademicService service;

    public AcademicController(AcademicService service) {
        this.service = service;
    }

    @GetMapping("/terms")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> terms() {
        return service.terms();
    }

    @PostMapping("/terms")
    @PreAuthorize("hasRole('REGULATOR')")
    ResponseEntity<Map<String, Object>> createTerm(@Valid @RequestBody TermRequest request) {
        Map<String, Object> created = service.createTerm(request);
        return ResponseEntity.created(URI.create("/api/terms/" + created.get("id"))).body(created);
    }

    @PutMapping("/terms/{id}")
    @PreAuthorize("hasRole('REGULATOR')")
    Map<String, Object> updateTerm(
            @PathVariable long id, @Valid @RequestBody TermRequest request) {
        return service.updateTerm(id, request);
    }

    @GetMapping("/service-plans")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> servicePlans(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId) {
        return service.servicePlans(schoolId, termId);
    }

    @PostMapping("/service-plans")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createServicePlan(
            @Valid @RequestBody ServicePlanRequest request) {
        Map<String, Object> created = service.createServicePlan(request);
        return ResponseEntity.created(URI.create("/api/service-plans/" + created.get("id")))
                .body(created);
    }

    @PutMapping("/service-plans/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateServicePlan(
            @PathVariable long id, @Valid @RequestBody ServicePlanRequest request) {
        return service.updateServicePlan(id, request);
    }

    @PostMapping("/service-plans/{id}/transitions")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    Map<String, Object> transitionServicePlan(
            @PathVariable long id, @Valid @RequestBody PlanTransitionRequest request) {
        return service.transitionServicePlan(id, request);
    }

    @GetMapping("/rooms")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> rooms(@RequestParam(required = false) Long schoolId) {
        return service.rooms(schoolId);
    }

    @PostMapping("/rooms")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createRoom(@Valid @RequestBody RoomRequest request) {
        Map<String, Object> created = service.createRoom(request);
        return ResponseEntity.created(URI.create("/api/rooms/" + created.get("id"))).body(created);
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateRoom(
            @PathVariable long id, @Valid @RequestBody RoomRequest request) {
        return service.updateRoom(id, request);
    }

    @GetMapping("/calendar-events")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> calendarEvents(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId) {
        return service.calendarEvents(schoolId, termId);
    }

    @PostMapping("/calendar-events")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createCalendarEvent(
            @Valid @RequestBody CalendarEventRequest request) {
        Map<String, Object> created = service.createCalendarEvent(request);
        return ResponseEntity.created(URI.create("/api/calendar-events/" + created.get("id")))
                .body(created);
    }

    @PutMapping("/calendar-events/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateCalendarEvent(
            @PathVariable long id, @Valid @RequestBody CalendarEventRequest request) {
        return service.updateCalendarEvent(id, request);
    }

    @GetMapping("/schedule-adjustments")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> scheduleAdjustments(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long offeringId) {
        return service.scheduleAdjustments(schoolId, offeringId);
    }

    @PostMapping("/sessions/{sessionId}/reschedule")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> reschedule(
            @PathVariable long sessionId,
            @Valid @RequestBody RescheduleRequest request) {
        Map<String, Object> created = service.reschedule(sessionId, request);
        return ResponseEntity.created(
                        URI.create("/api/schedule-adjustments/" + created.get("id")))
                .body(created);
    }

    public record TermRequest(
            @NotBlank @Size(max = 32) String termCode,
            @NotBlank @Size(max = 128) String termName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|CLOSED|ARCHIVED") String status) {}

    public record ServicePlanRequest(
            Long schoolId,
            @Positive long termId,
            @NotBlank @Size(max = 32) String planCode,
            @NotBlank @Size(max = 128) String planName,
            String description) {}

    public record PlanTransitionRequest(
            @NotBlank
                    @Pattern(regexp = "SUBMITTED|FILED|RETURNED|ACTIVE|CLOSED|ARCHIVED")
                    String targetStatus,
            @Size(max = 500) String reason) {}

    public record RoomRequest(
            Long schoolId,
            @NotBlank @Size(max = 32) String roomCode,
            @NotBlank @Size(max = 128) String roomName,
            @Size(max = 255) String location,
            @Positive int capacity,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    public record CalendarEventRequest(
            Long schoolId,
            @Positive long termId,
            @NotNull LocalDate eventDate,
            @NotBlank
                    @Pattern(regexp = "TEACHING_DAY|MAKEUP_DAY|HOLIDAY|SUSPENDED")
                    String dayType,
            @NotBlank @Size(max = 128) String eventName,
            @Size(max = 500) String description) {}

    public record RescheduleRequest(
            @NotNull LocalDate sessionDate,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @Positive long roomId,
            @NotBlank @Size(max = 500) String reason) {}
}
