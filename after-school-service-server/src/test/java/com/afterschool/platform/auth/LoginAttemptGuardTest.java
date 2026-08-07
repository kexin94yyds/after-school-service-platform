package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class LoginAttemptGuardTest {

    @Test
    void appliesBackoffAfterRepeatedFailuresAndSeparatesSources() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard guard = new LoginAttemptGuard(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            guard.checkAllowed("operator", "192.0.2.10");
            guard.recordFailure("operator", "192.0.2.10");
        }

        assertThatThrownBy(() -> guard.checkAllowed("operator", "192.0.2.10"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatCode(() -> guard.checkAllowed("operator", "192.0.2.11"))
                .doesNotThrowAnyException();

        clock.advanceSeconds(1);
        assertThatCode(() -> guard.checkAllowed("operator", "192.0.2.10"))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsFailureHistory() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard guard = new LoginAttemptGuard(clock);
        for (int attempt = 0; attempt < 5; attempt++) {
            guard.recordFailure("operator", "192.0.2.10");
        }

        guard.recordSuccess("operator", "192.0.2.10");

        assertThatCode(() -> guard.checkAllowed("operator", "192.0.2.10"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordsAndBlocksNewKeyWhenCapacityIsAlreadyFull() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard guard = new LoginAttemptGuard(clock, 3);
        guard.recordFailure("existing-1", "192.0.2.1");
        guard.recordFailure("existing-2", "192.0.2.2");
        guard.recordFailure("existing-3", "192.0.2.3");
        assertThat(guard.trackedKeyCount()).isEqualTo(3);

        for (int attempt = 0; attempt < 5; attempt++) {
            guard.recordFailure("target", "192.0.2.100");
            assertThat(guard.trackedKeyCount()).isEqualTo(3);
        }

        assertThatThrownBy(() -> guard.checkAllowed("target", "192.0.2.100"))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-31T10:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
