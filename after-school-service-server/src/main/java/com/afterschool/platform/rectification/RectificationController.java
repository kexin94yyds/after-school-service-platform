package com.afterschool.platform.rectification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rectifications")
@PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
public class RectificationController {

    private final RectificationService service;

    public RectificationController(RectificationService service) {
        this.service = service;
    }

    @PostMapping("/alerts/{alertId}/notice")
    @PreAuthorize("hasRole('REGULATOR')")
    Map<String, Object> issueNotice(
            @PathVariable long alertId,
            @Valid @RequestBody NoticeRequest request) {
        return service.issueNotice(alertId, request);
    }

    @GetMapping("/alerts/{alertId}/notice")
    Map<String, Object> notice(@PathVariable long alertId) {
        return service.notice(alertId);
    }

    @GetMapping("/alerts/{alertId}/materials")
    List<Map<String, Object>> materials(@PathVariable long alertId) {
        return service.materials(alertId);
    }

    @PostMapping(
            value = "/alerts/{alertId}/materials",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> uploadMaterial(
            @PathVariable long alertId,
            @RequestPart("file") MultipartFile file) {
        return service.uploadMaterial(alertId, file);
    }

    @GetMapping("/materials/{materialId}/download")
    ResponseEntity<InputStreamResource> download(@PathVariable long materialId)
            throws IOException {
        RectificationService.MaterialDownload material = service.download(materialId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(material.contentType()));
        headers.setContentLength(material.size());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(material.originalName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(Files.newInputStream(material.path())));
    }

    public record NoticeRequest(
            @NotBlank @Size(max = 128) String title,
            @NotBlank @Size(max = 2000) String requirements,
            @NotNull @Future LocalDateTime dueAt) {}
}
