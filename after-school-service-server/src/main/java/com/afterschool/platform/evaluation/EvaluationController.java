package com.afterschool.platform.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('GUARDIAN')")
    ResponseEntity<Map<String, Object>> submit(
            @Valid @RequestBody EvaluationRequest request) {
        Map<String, Object> created = service.submit(
                request.studentId(),
                request.offeringId(),
                request.courseRating() == null ? request.rating() : request.courseRating(),
                request.teacherRating() == null ? request.rating() : request.teacherRating(),
                request.comment());
        return ResponseEntity.created(
                        URI.create("/api/evaluations/" + created.get("id")))
                .body(created);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('GUARDIAN')")
    List<Map<String, Object>> mine() {
        return service.mine();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    List<Map<String, Object>> evaluations(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) LocalDateTime submittedFrom,
            @RequestParam(required = false) LocalDateTime submittedTo) {
        return service.list(
                schoolId,
                termId,
                category,
                minRating,
                maxRating,
                submittedFrom,
                submittedTo);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    Map<String, Object> summary(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) LocalDateTime submittedFrom,
            @RequestParam(required = false) LocalDateTime submittedTo) {
        return service.summary(
                schoolId,
                termId,
                category,
                submittedFrom,
                submittedTo);
    }

    public record EvaluationRequest(
            @Positive long studentId,
            @Positive long offeringId,
            @Min(1) @Max(5) Integer courseRating,
            @Min(1) @Max(5) Integer teacherRating,
            @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment) {}
}
