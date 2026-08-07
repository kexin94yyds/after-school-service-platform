package com.afterschool.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

class FlywayProfileConfigurationTest {

    private static final String OUT_OF_ORDER = "spring.flyway.out-of-order";

    @Test
    void enablesOutOfOrderOnlyForDemoProfile() {
        ConfigurableEnvironment environment = loadConfiguration("demo");

        assertThat(environment.getProperty(OUT_OF_ORDER, Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.locations[1]"))
                .isEqualTo("classpath:db/demo");
    }

    @Test
    void keepsDefaultAndProductionProfilesStrict() {
        assertThat(loadConfiguration().getProperty(OUT_OF_ORDER, Boolean.class, false)).isFalse();
        assertThat(loadConfiguration("prod").getProperty(OUT_OF_ORDER, Boolean.class)).isFalse();
    }

    @Test
    void appliesProductionReverseProxyTrustBoundary() {
        ConfigurableEnvironment environment = loadConfiguration("prod");

        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.forward-headers-strategy"))
                .isEqualTo("native");
        assertThat(environment.getProperty("server.tomcat.remoteip.internal-proxies"))
                .isEqualTo("127.0.0.1/32, ::1/128");
        assertThat(environment.getProperty(
                        "server.servlet.session.cookie.secure", Boolean.class))
                .isTrue();
    }

    private ConfigurableEnvironment loadConfiguration(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
