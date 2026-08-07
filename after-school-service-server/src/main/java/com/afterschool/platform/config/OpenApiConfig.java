package com.afterschool.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("中小学课后服务选课与教务监管平台 API")
                .version("v1")
                .description("多学校课后服务监管、选课与教学管理接口"));
    }
}

