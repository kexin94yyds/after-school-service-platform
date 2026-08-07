package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class DemoPasswordHashTest {

    @Test
    void documentedDemoPasswordMatchesCredentialMigrationHash() {
        String seededHash =
                "{bcrypt}$2y$10$z45mz6/iAGhpW/xaKaD5d.r6n1LEAAp.3JH4wcPHlxLcNiQUxRI0u";

        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                        .matches("123456", seededHash))
                .isTrue();
    }
}
