package com.afterschool.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    @Test
    void mapsMethodAuthorizationDenialsToForbidden() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AuthorizationDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("code", "FORBIDDEN")
                .containsEntry("message", "没有权限执行此操作");
    }

    @Test
    void mapsTypeConversionAndMalformedJsonToStableBadRequests()
            throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new ParsingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/parse/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/parse")
                        .contentType("application/json")
                        .content("{\"id\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @RestController
    private static class ParsingController {

        @org.springframework.web.bind.annotation.GetMapping("/parse/{id}")
        Map<String, Long> parsePath(@PathVariable long id) {
            return Map.of("id", id);
        }

        @PostMapping("/parse")
        Map<String, Long> parseBody(@RequestBody Payload payload) {
            return Map.of("id", payload.id());
        }
    }

    private record Payload(long id) {}
}
