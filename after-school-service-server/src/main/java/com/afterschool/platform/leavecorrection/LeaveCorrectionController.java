package com.afterschool.platform.leavecorrection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LeaveCorrectionController {

    private final LeaveCorrectionService service;

    public LeaveCorrectionController(LeaveCorrectionService service) {
        this.service = service;
    }

    @GetMapping("/guardian/students/{studentId}/sessions")
    @PreAuthorize("hasRole('GUARDIAN')")
    List<Map<String, Object>> guardianLeaveSessions(
            @PathVariable long studentId) {
        return service.guardianLeaveSessions(studentId);
    }

    @GetMapping("/student/sessions")
    @PreAuthorize("hasRole('STUDENT')")
    List<Map<String, Object>> studentLeaveSessions() {
        return service.studentLeaveSessions();
    }

    @GetMapping("/leave-requests")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER','GUARDIAN','STUDENT')")
    List<Map<String, Object>> leaveRequests(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long offeringId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String status) {
        return service.leaveRequests(schoolId, offeringId, sessionId, status);
    }

    @PostMapping("/leave-requests")
    @PreAuthorize("hasRole('STUDENT')")
    Map<String, Object> submitLeave(@Valid @RequestBody LeaveSubmission request) {
        return service.submitLeave(request);
    }

    @PostMapping("/leave-requests/{id}/withdraw")
    @PreAuthorize("hasRole('STUDENT')")
    Map<String, Object> withdrawLeave(@PathVariable long id) {
        return service.withdrawLeave(id);
    }

    @PutMapping("/leave-requests/{id}/review")
    @PreAuthorize("hasRole('TEACHER')")
    Map<String, Object> reviewLeave(
            @PathVariable long id, @Valid @RequestBody LeaveReview request) {
        return service.reviewLeave(id, request);
    }

    @GetMapping("/attendance-corrections")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER')")
    List<Map<String, Object>> corrections(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long offeringId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String status) {
        return service.corrections(schoolId, offeringId, sessionId, status);
    }

    @PostMapping("/attendance-corrections")
    @PreAuthorize("hasRole('TEACHER')")
    Map<String, Object> requestCorrection(@Valid @RequestBody CorrectionSubmission request) {
        return service.requestCorrection(request);
    }

    @PostMapping("/attendance-corrections/{id}/cancel")
    @PreAuthorize("hasRole('TEACHER')")
    Map<String, Object> cancelCorrection(@PathVariable long id) {
        return service.cancelCorrection(id);
    }

    @PutMapping("/attendance-corrections/{id}/review")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> reviewCorrection(
            @PathVariable long id, @Valid @RequestBody CorrectionReview request) {
        return service.reviewCorrection(id, request);
    }

    @GetMapping("/attendance/{attendanceId}/revisions")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN','TEACHER','GUARDIAN','STUDENT')")
    List<Map<String, Object>> revisions(@PathVariable long attendanceId) {
        return service.revisions(attendanceId);
    }

    public record LeaveSubmission(
            @Positive long sessionId,
            @Positive long studentId,
            @NotBlank @Size(max = 500) String reason) {}

    public record LeaveReview(
            @NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @Size(max = 500) String remark) {}

    public record CorrectionSubmission(
            @Positive long sessionId,
            @Positive long studentId,
            @NotNull @Pattern(regexp = "PRESENT|LATE|LEAVE|ABSENT")
                    String requestedStatus,
            @Size(max = 255) String requestedRemark,
            @NotBlank @Size(max = 500) String reason) {}

    public record CorrectionReview(
            @NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @Size(max = 500) String remark) {}
}
