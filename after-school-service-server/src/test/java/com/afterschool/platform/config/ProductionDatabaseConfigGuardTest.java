package com.afterschool.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionDatabaseConfigGuardTest {

    private static final String TLS_URL =
            "jdbc:mysql://db.example:3306/app?sslMode=VERIFY_IDENTITY"
                    + "&connectionTimeZone=%2B08%3A00"
                    + "&forceConnectionTimeZoneToSession=true";

    @Test
    void acceptsRequiredTlsProductionConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatCode(() -> new ProductionDatabaseConfigGuard(
                        TLS_URL,
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDemoProfileCombinedWithProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "demo");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        TLS_URL,
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    void rejectsProductionUrlWithoutCertificateVerification() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        "jdbc:mysql://db.example:3306/app?useSSL=true"
                                + "&connectionTimeZone=%2B08%3A00"
                                + "&forceConnectionTimeZoneToSession=true",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFY_IDENTITY");
    }

    @Test
    void rejectsTlsModeHiddenInsideAnotherProperty() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        "jdbc:mysql://db.example:3306/app?note=sslMode%3DVERIFY_IDENTITY"
                                + "&connectionTimeZone=%2B08%3A00"
                                + "&forceConnectionTimeZoneToSession=true",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFY_IDENTITY");
    }

    @Test
    void rejectsDuplicateTlsModeProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        TLS_URL + "&sslMode=DISABLED",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFY_IDENTITY");
    }

    @Test
    void rejectsProductionConnectionsWithoutChinaStandardTime() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        "jdbc:mysql://db.example:3306/app?sslMode=VERIFY_IDENTITY"
                                + "&connectionTimeZone=UTC"
                                + "&forceConnectionTimeZoneToSession=true",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("+08:00");
    }

    @Test
    void rejectsProductionConnectionsThatDoNotForceSessionTimeZone() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        "jdbc:mysql://db.example:3306/app?sslMode=VERIFY_IDENTITY"
                                + "&connectionTimeZone=%2B08%3A00"
                                + "&forceConnectionTimeZoneToSession=false",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forceConnectionTimeZoneToSession=true");
    }

    @Test
    void rejectsDuplicateProductionTimeZoneProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        TLS_URL + "&connectionTimeZone=UTC",
                        "app_user",
                        "non-empty-secret",
                        false,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly once");
    }

    @Test
    void rejectsOutOfOrderPropertyOverrideAtConstruction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionDatabaseConfigGuard(
                        TLS_URL,
                        "app_user",
                        "non-empty-secret",
                        true,
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-order");
    }

    @Test
    void acceptsOnlyResolvedProductionMigrationLocationBeforeMigrating() {
        ProductionDatabaseConfigGuard guard = validGuard();
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getLocations()).thenReturn(new Location[] {
            new Location("classpath:/db/migration/")
        });

        guard.productionFlywayMigrationStrategy().migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void rejectsDemoMigrationLocationBeforeMigrating() {
        ProductionDatabaseConfigGuard guard = validGuard();
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getLocations()).thenReturn(new Location[] {
            new Location("classpath:db/migration"),
            new Location("classpath:db/demo")
        });

        assertThatThrownBy(() -> guard.productionFlywayMigrationStrategy().migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath:db/migration");
        verify(flyway, never()).migrate();
    }

    @Test
    void rejectsOutOfOrderRuntimeConfigurationBeforeMigrating() {
        ProductionDatabaseConfigGuard guard = validGuard();
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.isOutOfOrder()).thenReturn(true);
        when(configuration.getLocations()).thenReturn(new Location[] {
            new Location("classpath:db/migration")
        });

        assertThatThrownBy(() -> guard.productionFlywayMigrationStrategy().migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-order");
        verify(flyway, never()).migrate();
    }

    @Test
    void rejectsParentOrExternalMigrationLocations() {
        assertThatThrownBy(() -> ProductionDatabaseConfigGuard
                        .requireProductionMigrationLocations(new Location[] {
                            new Location("classpath:db")
                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionDatabaseConfigGuard
                        .requireProductionMigrationLocations(new Location[] {
                            new Location("filesystem:/srv/app/migrations")
                        }))
                .isInstanceOf(IllegalStateException.class);
    }

    private ProductionDatabaseConfigGuard validGuard() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new ProductionDatabaseConfigGuard(
                TLS_URL,
                "app_user",
                "non-empty-secret",
                false,
                environment);
    }
}
