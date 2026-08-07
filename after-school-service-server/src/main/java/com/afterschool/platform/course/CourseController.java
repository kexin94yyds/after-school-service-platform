package com.afterschool.platform.course;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> courses(@RequestParam(required = false) Long schoolId) {
        return service.courses(schoolId);
    }

    @PostMapping("/courses")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createCourse(@Valid @RequestBody CourseRequest request) {
        Map<String, Object> created = service.createCourse(request);
        return ResponseEntity.created(URI.create("/api/courses/" + created.get("id"))).body(created);
    }

    @PutMapping("/courses/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateCourse(@PathVariable long id, @Valid @RequestBody CourseRequest request) {
        return service.updateCourse(id, request);
    }

    @GetMapping("/offerings")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> offerings(@RequestParam(required = false) Long schoolId) {
        return service.offerings(schoolId);
    }

    @PostMapping("/offerings")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createOffering(@Valid @RequestBody OfferingRequest request) {
        Map<String, Object> created = service.createOffering(request);
        return ResponseEntity.created(URI.create("/api/offerings/" + created.get("id"))).body(created);
    }

    @PutMapping("/offerings/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateOffering(
            @PathVariable long id, @Valid @RequestBody OfferingRequest request) {
        return service.updateOffering(id, request);
    }

    public record CourseRequest(
            Long schoolId,
            @NotBlank @Size(max = 32) String courseCode,
            @NotBlank @Size(max = 128) String courseName,
            @NotBlank @Size(max = 64) String category,
            String description,
            @Min(1) @Max(12) int targetGradeMin,
            @Min(1) @Max(12) int targetGradeMax,
            @Positive int defaultCapacity,
            @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE") String status) {}

    public record OfferingRequest(
            Long schoolId,
            @Positive long courseId,
            @Positive long teacherId,
            @NotBlank @Size(max = 32) String offeringCode,
            @NotBlank @Size(max = 32) String term,
            @Min(1) @Max(7) int weekDay,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull LocalDateTime enrollmentStart,
            @NotNull LocalDateTime enrollmentEnd,
            @Positive int capacity,
            @NotBlank @Size(max = 64) String classroom,
            @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|CLOSED|FINISHED|CANCELED") String status,
            @Positive Long termId,
            @Positive Long planId,
            @Positive Long roomId) {}
}
