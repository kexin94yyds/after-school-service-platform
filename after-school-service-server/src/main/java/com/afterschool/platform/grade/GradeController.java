package com.afterschool.platform.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grades")
public class GradeController {

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('STUDENT','GUARDIAN','TEACHER','SCHOOL_ADMIN')")
    List<Map<String, Object>> grades(@RequestParam(required = false) Long offeringId) {
        return service.list(offeringId);
    }

    @GetMapping("/roster")
    @PreAuthorize("hasRole('TEACHER')")
    List<Map<String, Object>> roster(@RequestParam @Positive long offeringId) {
        return service.roster(offeringId);
    }

    @PutMapping
    @PreAuthorize("hasRole('TEACHER')")
    Map<String, Object> save(@Valid @RequestBody GradeRequest request) {
        return service.save(request);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('TEACHER','SCHOOL_ADMIN')")
    List<Map<String, Object>> summary() {
        return service.summary();
    }

    @GetMapping("/export.xlsx")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<byte[]> export() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("student-grades.xlsx")
                .build());
        return ResponseEntity.ok().headers(headers).body(service.gradesXlsx());
    }

    @GetMapping("/{gradeId}/revisions")
    @PreAuthorize("hasAnyRole('TEACHER','SCHOOL_ADMIN')")
    List<Map<String, Object>> revisions(@PathVariable long gradeId) {
        return service.revisions(gradeId);
    }

    public record GradeRequest(
            @Positive long offeringId,
            @Positive long studentId,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal score,
            @Size(max = 1000) String learningEvaluation) {}
}
