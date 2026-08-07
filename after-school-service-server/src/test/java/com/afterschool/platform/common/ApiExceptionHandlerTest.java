package com.afterschool.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;

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
}
