package com.afterschool.platform.organization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping("/schools")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    List<Map<String, Object>> schools() {
        return service.schools();
    }

    @PostMapping("/schools")
    @PreAuthorize("hasRole('REGULATOR')")
    ResponseEntity<Map<String, Object>> createSchool(@Valid @RequestBody SchoolRequest request) {
        Map<String, Object> created = service.createSchool(request);
        return ResponseEntity.created(URI.create("/api/schools/" + created.get("id"))).body(created);
    }

    @PutMapping("/schools/{id}")
    @PreAuthorize("hasRole('REGULATOR')")
    Map<String, Object> updateSchool(@PathVariable long id, @Valid @RequestBody SchoolRequest request) {
        return service.updateSchool(id, request);
    }

    @GetMapping("/schools/{schoolId}/admins")
    @PreAuthorize("hasRole('REGULATOR')")
    List<Map<String, Object>> schoolAdmins(@PathVariable long schoolId) {
        return service.schoolAdmins(schoolId);
    }

    @PostMapping("/schools/{schoolId}/admins")
    @PreAuthorize("hasRole('REGULATOR')")
    ResponseEntity<Map<String, Object>> createSchoolAdmin(
            @PathVariable long schoolId,
            @Valid @RequestBody SchoolAdminRequest request) {
        Map<String, Object> created = service.createSchoolAdmin(schoolId, request);
        return ResponseEntity.created(
                        URI.create("/api/schools/" + schoolId + "/admins/" + created.get("id")))
                .body(created);
    }

    @PutMapping("/schools/{schoolId}/admins/{id}")
    @PreAuthorize("hasRole('REGULATOR')")
    Map<String, Object> updateSchoolAdmin(
            @PathVariable long schoolId,
            @PathVariable long id,
            @Valid @RequestBody SchoolAdminRequest request) {
        return service.updateSchoolAdmin(schoolId, id, request);
    }

    @GetMapping("/classes")
    @PreAuthorize("hasAnyRole('REGULATOR','SCHOOL_ADMIN')")
    List<Map<String, Object>> classes(@RequestParam(required = false) Long schoolId) {
        return service.classes(schoolId);
    }

    @PostMapping("/classes")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    ResponseEntity<Map<String, Object>> createClass(@Valid @RequestBody ClassRequest request) {
        Map<String, Object> created = service.createClass(request);
        return ResponseEntity.created(URI.create("/api/classes/" + created.get("id"))).body(created);
    }

    @PutMapping("/classes/{id}")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    Map<String, Object> updateClass(@PathVariable long id, @Valid @RequestBody ClassRequest request) {
        return service.updateClass(id, request);
    }

    public record SchoolRequest(
            @NotBlank @Size(max = 32) String schoolCode,
            @NotBlank @Size(max = 128) String schoolName,
            @NotBlank @Size(max = 32) String districtCode,
            @Size(max = 255) String address,
            @Size(max = 32) String contactPhone,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    public record SchoolAdminRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 64) String displayName,
            @Size(max = 32) String mobile,
            @Size(min = 12, max = 72) String password,
            @NotNull Boolean enabled) {}

    public record ClassRequest(
            Long schoolId,
            @NotBlank @Size(max = 64) String className,
            @Min(1) @Max(12) int grade,
            @NotBlank @Size(max = 16) String schoolYear,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}
}
