package com.afterschool.platform.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
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
public class PeopleController {

    private final PeopleService service;

    public PeopleController(PeopleService service) {
        this.service = service;
    }

    @GetMapping("/teachers")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    List<Map<String, Object>> teachers(@RequestParam(required = false) Long schoolId) {
        return service.teachers(schoolId);
    }

    @PostMapping("/teachers")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createTeacher(@Valid @RequestBody TeacherRequest request) {
        Map<String, Object> created = service.createTeacher(request);
        return ResponseEntity.created(URI.create("/api/teachers/" + created.get("id"))).body(created);
    }

    @PutMapping("/teachers/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateTeacher(@PathVariable long id, @Valid @RequestBody TeacherRequest request) {
        return service.updateTeacher(id, request);
    }

    @GetMapping("/students")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    List<Map<String, Object>> students(@RequestParam(required = false) Long schoolId) {
        return service.students(schoolId);
    }

    @PostMapping("/students")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createStudent(@Valid @RequestBody StudentRequest request) {
        Map<String, Object> created = service.createStudent(request);
        return ResponseEntity.created(URI.create("/api/students/" + created.get("id"))).body(created);
    }

    @PutMapping("/students/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateStudent(@PathVariable long id, @Valid @RequestBody StudentRequest request) {
        return service.updateStudent(id, request);
    }

    @GetMapping("/guardians")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    List<Map<String, Object>> guardians(@RequestParam(required = false) Long schoolId) {
        return service.guardians(schoolId);
    }

    @PostMapping("/guardians")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createGuardian(@Valid @RequestBody GuardianRequest request) {
        Map<String, Object> created = service.createGuardian(request);
        return ResponseEntity.created(URI.create("/api/guardians/" + created.get("id"))).body(created);
    }

    @PutMapping("/guardians/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateGuardian(@PathVariable long id, @Valid @RequestBody GuardianRequest request) {
        return service.updateGuardian(id, request);
    }

    public record TeacherRequest(
            Long schoolId,
            @NotBlank @Size(max = 32) String teacherNo,
            @NotBlank @Size(max = 64) String fullName,
            @NotBlank @Size(max = 64) String username,
            @Size(min = 12, max = 72) String password,
            @Size(max = 32) String phone,
            @Size(max = 64) String title,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    public record StudentRequest(
            Long schoolId,
            long classId,
            @NotBlank @Size(max = 32) String studentNo,
            @NotBlank @Size(max = 64) String fullName,
            @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
            LocalDate dateOfBirth,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    public record GuardianRequest(
            Long schoolId,
            @NotBlank @Size(max = 64) String fullName,
            @NotBlank @Size(max = 32) String mobile,
            @NotBlank @Size(max = 64) String username,
            @Size(min = 12, max = 72) String password,
            @NotEmpty List<Long> studentIds,
            @NotBlank @Size(max = 32) String relationship,
            boolean primary,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}
}
