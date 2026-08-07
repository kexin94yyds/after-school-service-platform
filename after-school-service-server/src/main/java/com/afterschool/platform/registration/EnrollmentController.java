package com.afterschool.platform.registration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @GetMapping("/guardian/students")
    @PreAuthorize("hasRole('GUARDIAN')")
    List<Map<String, Object>> guardianStudents() {
        return service.guardianStudents();
    }

    @GetMapping("/guardian/students/{studentId}/offerings")
    @PreAuthorize("hasRole('GUARDIAN')")
    List<Map<String, Object>> guardianOfferings(@PathVariable long studentId) {
        return service.guardianOfferings(studentId);
    }

    @GetMapping("/guardian/students/{studentId}/attendance")
    @PreAuthorize("hasRole('GUARDIAN')")
    List<Map<String, Object>> guardianAttendance(@PathVariable long studentId) {
        return service.guardianAttendance(studentId);
    }

    @GetMapping("/enrollments")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER','GUARDIAN')")
    List<Map<String, Object>> enrollments(@RequestParam(required = false) Long schoolId) {
        return service.enrollments(schoolId);
    }

    @PostMapping("/enrollments")
    @PreAuthorize("hasRole('GUARDIAN')")
    ResponseEntity<Map<String, Object>> enroll(@Valid @RequestBody EnrollmentRequest request) {
        Map<String, Object> created = service.enroll(request);
        return ResponseEntity.created(URI.create("/api/enrollments/" + created.get("id"))).body(created);
    }

    @DeleteMapping("/enrollments/{id}")
    @PreAuthorize("hasAnyRole('GUARDIAN','SCHOOL_ADMIN')")
    ResponseEntity<Void> cancel(@PathVariable long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    public record EnrollmentRequest(
            @Positive long studentId,
            @Positive long offeringId) {}
}
