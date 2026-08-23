package com.afterschool.platform.registration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

    @GetMapping("/guardian/students/{studentId}/attendance/monthly")
    @PreAuthorize("hasRole('GUARDIAN')")
    Map<String, Object> guardianMonthlyAttendance(
            @PathVariable long studentId,
            @RequestParam String month) {
        return service.guardianMonthlyAttendance(studentId, month);
    }

    @GetMapping("/student/profile")
    @PreAuthorize("hasRole('STUDENT')")
    Map<String, Object> studentProfile() {
        return service.studentProfile();
    }

    @GetMapping("/student/offerings")
    @PreAuthorize("hasRole('STUDENT')")
    List<Map<String, Object>> studentOfferings() {
        return service.studentOfferings();
    }

    @GetMapping("/student/attendance")
    @PreAuthorize("hasRole('STUDENT')")
    List<Map<String, Object>> studentAttendance() {
        return service.studentAttendance();
    }

    @GetMapping("/student/attendance/monthly")
    @PreAuthorize("hasRole('STUDENT')")
    Map<String, Object> studentMonthlyAttendance(@RequestParam String month) {
        return service.studentMonthlyAttendance(month);
    }

    @GetMapping("/student/schedule")
    @PreAuthorize("hasRole('STUDENT')")
    List<Map<String, Object>> studentSchedule() {
        return service.studentSchedule();
    }

    @GetMapping("/enrollments")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER','GUARDIAN','STUDENT')")
    List<Map<String, Object>> enrollments(@RequestParam(required = false) Long schoolId) {
        return service.enrollments(schoolId);
    }

    @GetMapping("/enrollments/roster.xlsx")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    ResponseEntity<byte[]> rosterXlsx(@RequestParam @Positive long offeringId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("enrollment-roster.xlsx")
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .body(service.rosterXlsx(offeringId));
    }

    @GetMapping("/enrollments/class-roster.xlsx")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<byte[]> classRosterXlsx(@RequestParam @Positive long classId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("class-enrollment-roster.xlsx")
                .build());
        return ResponseEntity.ok().headers(headers).body(service.classRosterXlsx(classId));
    }

    @PostMapping("/enrollments")
    @PreAuthorize("hasRole('STUDENT')")
    ResponseEntity<Map<String, Object>> enroll(@Valid @RequestBody EnrollmentRequest request) {
        Map<String, Object> created = service.enroll(request);
        return ResponseEntity.created(URI.create("/api/enrollments/" + created.get("id"))).body(created);
    }

    @DeleteMapping("/enrollments/{id}")
    @PreAuthorize("hasAnyRole('STUDENT','SCHOOL_ADMIN')")
    ResponseEntity<Void> cancel(@PathVariable long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/enrollments/{id}/switch")
    @PreAuthorize("hasRole('STUDENT')")
    Map<String, Object> switchEnrollment(
            @PathVariable long id,
            @Valid @RequestBody EnrollmentSwitchRequest request) {
        return service.switchEnrollment(id, request.newOfferingId());
    }

    @GetMapping("/enrollments/{id}/actions")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','GUARDIAN','STUDENT')")
    List<Map<String, Object>> enrollmentActions(@PathVariable long id) {
        return service.enrollmentActions(id);
    }

    public record EnrollmentRequest(
            @Positive long studentId,
            @Positive long offeringId) {}

    public record EnrollmentSwitchRequest(@Positive long newOfferingId) {}
}
