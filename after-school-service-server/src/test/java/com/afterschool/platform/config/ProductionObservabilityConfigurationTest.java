package com.afterschool.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

class ProductionObservabilityConfigurationTest {

    @Test
    void isolatesManagementEndpointsAndExposesOnlyHealthAndPrometheus() {
        ConfigurableEnvironment environment = productionEnvironment();

        assertThat(environment.getProperty("management.server.address"))
                .isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.server.port", Integer.class))
                .isEqualTo(8082);
        assertThat(csvProperty(environment, "management.endpoints.web.exposure.include"))
                .containsExactlyInAnyOrder("health", "prometheus");
        assertThat(environment.getProperty(
                        "management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(environment.getProperty(
                        "management.endpoint.health.show-components"))
                .isEqualTo("never");
    }

    @Test
    void publishesMainPortProbesAndMakesDatabasePartOfReadinessOnly() {
        ConfigurableEnvironment environment = productionEnvironment();

        assertThat(environment.getProperty(
                        "management.endpoint.health.probes.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty(
                        "management.endpoint.health.probes.add-additional-paths", Boolean.class))
                .isTrue();
        assertThat(csvProperty(
                        environment,
                        "management.endpoint.health.group.liveness.include"))
                .containsExactly("livenessState");
        assertThat(csvProperty(
                        environment,
                        "management.endpoint.health.group.readiness.include"))
                .containsExactlyInAnyOrder("readinessState", "db");
    }

    @Test
    void usesGracefulShutdownWithABoundedDrainPeriod() {
        ConfigurableEnvironment environment = productionEnvironment();

        assertThat(environment.getProperty("server.shutdown"))
                .isEqualTo("graceful");
        assertThat(environment.getProperty(
                        "spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("30s");
    }

    @Test
    void disablesOpenApiAndKeepsRectificationFilesInSystemdStateDirectory() {
        ConfigurableEnvironment environment = productionEnvironment();

        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("app.rectification.storage-path"))
                .isEqualTo("/var/lib/after-school-service/rectification-materials");
    }

    private Set<String> csvProperty(
            ConfigurableEnvironment environment, String propertyName) {
        String value = environment.getRequiredProperty(propertyName);
        return Set.of(value.split(","));
    }

    private ConfigurableEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
