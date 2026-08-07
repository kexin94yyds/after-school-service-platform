package com.afterschool.platform.common.web;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicSystemController {

    @GetMapping("/system-info")
    Map<String, String> systemInfo() {
        return Map.of(
                "name", "中小学课后服务选课与教务监管平台",
                "status", "ready");
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        // Resolve the deferred token so CookieCsrfTokenRepository emits XSRF-TOKEN.
        // The response deliberately does not expose the XOR-masked request token:
        // SPA clients must echo the raw cookie value in the request header.
        token.getToken();
        return Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName());
    }
}
