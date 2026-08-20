package com.afterschool.platform.supervision;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/regulator/notifications")
@PreAuthorize("hasRole('REGULATOR')")
public class RegulatorNotificationController {

    private final SupervisionService service;

    public RegulatorNotificationController(SupervisionService service) {
        this.service = service;
    }

    @GetMapping
    List<Map<String, Object>> notifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.notifications(unreadOnly);
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable long id) {
        service.markNotificationRead(id);
        return ResponseEntity.noContent().build();
    }
}
