package com.afterschool.platform.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(@RequestParam(required = false) Long schoolId) {
        return service.overview(schoolId);
    }

    @GetMapping("/teacher-hours")
    List<Map<String, Object>> teacherHours(@RequestParam(required = false) Long schoolId) {
        return service.teacherHours(schoolId);
    }

    @GetMapping("/course-performance")
    List<Map<String, Object>> coursePerformance(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return service.coursePerformance(
                schoolId, termId, fromDate, toDate, category, status);
    }

    @GetMapping(
            value = "/course-performance.csv",
            produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> coursePerformanceCsv(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        byte[] csv = service.coursePerformanceCsv(
                schoolId, termId, fromDate, toDate, category, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(
                MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("course-performance.csv")
                .build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @GetMapping(value = "/course-performance.xlsx")
    ResponseEntity<byte[]> coursePerformanceXlsx(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return xlsx(
                "course-performance.xlsx",
                service.coursePerformanceXlsx(
                        schoolId, termId, fromDate, toDate, category, status));
    }

    @GetMapping("/rectifications")
    List<Map<String, Object>> rectifications(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) LocalDate detectedFrom,
            @RequestParam(required = false) LocalDate detectedTo) {
        return service.rectifications(schoolId, detectedFrom, detectedTo);
    }

    @GetMapping(value = "/rectifications.xlsx")
    ResponseEntity<byte[]> rectificationsXlsx(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) LocalDate detectedFrom,
            @RequestParam(required = false) LocalDate detectedTo) {
        return xlsx(
                "rectification-report.xlsx",
                service.rectificationsXlsx(schoolId, detectedFrom, detectedTo));
    }

    private ResponseEntity<byte[]> xlsx(String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename)
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
