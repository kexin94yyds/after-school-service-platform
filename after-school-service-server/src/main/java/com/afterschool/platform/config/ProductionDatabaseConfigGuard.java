package com.afterschool.platform.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("prod")
public class ProductionDatabaseConfigGuard {

    private static final Location PRODUCTION_MIGRATION_LOCATION =
            new Location("classpath:db/migration");

    public ProductionDatabaseConfigGuard(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.flyway.out-of-order:false}") boolean flywayOutOfOrder,
            Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("demo")) {
            throw new IllegalStateException(
                    "The demo profile must never be combined with prod");
        }
        if (url == null || url.isBlank()
                || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Production DB_URL, DB_USERNAME and DB_PASSWORD must be non-empty");
        }
        if (!usesCertificateIdentityVerification(url)) {
            throw new IllegalStateException(
                    "Production DB_URL must use sslMode=VERIFY_IDENTITY");
        }
        if (!setsChinaStandardTime(url)) {
            throw new IllegalStateException(
                    "Production DB_URL must set connectionTimeZone=+08:00 and "
                            + "forceConnectionTimeZoneToSession=true exactly once");
        }
        requireStrictProductionMigrationOrder(flywayOutOfOrder);
    }

    @Bean
    FlywayMigrationStrategy productionFlywayMigrationStrategy() {
        return flyway -> {
            requireStrictProductionMigrationOrder(
                    flyway.getConfiguration().isOutOfOrder());
            requireProductionMigrationLocations(
                    flyway.getConfiguration().getLocations());
            flyway.migrate();
        };
    }

    static void requireStrictProductionMigrationOrder(boolean outOfOrder) {
        if (outOfOrder) {
            throw new IllegalStateException(
                    "Production Flyway out-of-order must remain disabled");
        }
    }

    static void requireProductionMigrationLocations(Location[] locations) {
        if (locations == null
                || locations.length != 1
                || !PRODUCTION_MIGRATION_LOCATION.equals(locations[0])) {
            throw new IllegalStateException(
                    "Production Flyway locations must resolve exactly to "
                            + "classpath:db/migration");
        }
    }

    private boolean usesCertificateIdentityVerification(String url) {
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            return false;
        }
        return hasUniqueQueryParameter(url, "sslMode", "VERIFY_IDENTITY");
    }

    private boolean setsChinaStandardTime(String url) {
        return hasUniqueQueryParameter(url, "connectionTimeZone", "+08:00")
                && hasUniqueQueryParameter(
                        url, "forceConnectionTimeZoneToSession", "true");
    }

    private boolean hasUniqueQueryParameter(
            String url, String requiredName, String requiredValue) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return false;
        }

        String value = null;
        int matches = 0;
        for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
            int separator = parameter.indexOf('=');
            String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!requiredName.equalsIgnoreCase(decode(rawName))) {
                continue;
            }
            matches++;
            value = separator < 0 ? "" : decode(parameter.substring(separator + 1));
        }
        return matches == 1 && requiredValue.equalsIgnoreCase(value);
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            return "";
        }
    }

}
