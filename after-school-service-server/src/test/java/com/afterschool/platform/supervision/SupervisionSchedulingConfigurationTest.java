package com.afterschool.platform.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterschool.platform.config.SupervisionSchedulingConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class SupervisionSchedulingConfigurationTest {

    @Test
    void defaultsToEnabledDailyChinaStandardTimeScan() throws Exception {
        ConfigurableEnvironment environment = new MockEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        Method scheduledMethod = SupervisionScanScheduler.class
                .getDeclaredMethod("scanScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = SupervisionScanScheduler.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(environment.getProperty(
                        "app.supervision.scan.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("app.supervision.scan.cron"))
                .isEqualTo("0 0 2 * * *");
        assertThat(environment.getProperty("app.supervision.scan.zone"))
                .isEqualTo("Asia/Shanghai");
        assertThat(environment.getProperty("app.supervision.scan.stale-after"))
                .isEqualTo("PT2H");
        assertThat(SupervisionSchedulingConfig.class
                        .isAnnotationPresent(EnableScheduling.class))
                .isTrue();
        assertThat(condition.prefix()).isEqualTo("app.supervision.scan");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(scheduled.cron())
                .isEqualTo("${app.supervision.scan.cron:0 0 2 * * *}");
        assertThat(scheduled.zone())
                .isEqualTo("${app.supervision.scan.zone:Asia/Shanghai}");
    }
}
