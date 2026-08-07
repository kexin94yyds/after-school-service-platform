package com.afterschool.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * A small in-process brake for repeated online password guesses.
 *
 * <p>Production deployments should also rate-limit at the trusted edge. This guard deliberately
 * keys attempts by a one-way fingerprint of account and source address, so neither identifier is
 * written to authentication-failure logs.
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);
    private static final int FAILURES_BEFORE_BACKOFF = 5;
    private static final int MAX_BACKOFF_SECONDS = 15 * 60;
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final Duration RETENTION = Duration.ofHours(1);

    private final Clock clock;
    private final int maxTrackedKeys;
    private final Map<String, AttemptState> attempts =
            new LinkedHashMap<>(16, 0.75f, true);
    private long operations;

    @Autowired
    public LoginAttemptGuard(Clock clock) {
        this(clock, MAX_TRACKED_KEYS);
    }

    LoginAttemptGuard(Clock clock, int maxTrackedKeys) {
        if (maxTrackedKeys <= 0) {
            throw new IllegalArgumentException("maxTrackedKeys must be positive");
        }
        this.clock = clock;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public synchronized void checkAllowed(String username, String remoteAddress) {
        Instant now = clock.instant();
        cleanUpIfNeeded(now);
        AttemptState state = attempts.get(key(username, remoteAddress));
        if (state != null && now.isBefore(state.blockedUntil())) {
            log.warn(
                    "login_rate_limited accountRef={} sourceRef={}",
                    fingerprint(normalizeUsername(username)),
                    fingerprint(normalizeSource(remoteAddress)));
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    public synchronized void recordFailure(String username, String remoteAddress) {
        Instant now = clock.instant();
        cleanUpIfNeeded(now);
        String key = key(username, remoteAddress);
        AttemptState previous = attempts.get(key);
        int failures = previous == null
                ? 1
                : Math.min(previous.failures() + 1, FAILURES_BEFORE_BACKOFF + 30);
        int backoffSeconds = backoffSeconds(failures);
        Instant blockedUntil = backoffSeconds == 0 ? now : now.plusSeconds(backoffSeconds);

        if (previous == null && attempts.size() >= maxTrackedKeys) {
            Iterator<String> eldest = attempts.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        attempts.put(key, new AttemptState(failures, blockedUntil, now));
        log.warn(
                "login_failed accountRef={} sourceRef={} consecutiveFailures={} backoffSeconds={}",
                fingerprint(normalizeUsername(username)),
                fingerprint(normalizeSource(remoteAddress)),
                failures,
                backoffSeconds);
    }

    public synchronized void recordSuccess(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
    }

    synchronized int trackedKeyCount() {
        return attempts.size();
    }

    private void cleanUpIfNeeded(Instant now) {
        operations++;
        if ((operations & 255) != 0) {
            return;
        }
        Instant cutoff = now.minus(RETENTION);
        Iterator<AttemptState> iterator = attempts.values().iterator();
        while (iterator.hasNext()) {
            AttemptState state = iterator.next();
            if (state.lastActivity().isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }

    private int backoffSeconds(int failures) {
        if (failures < FAILURES_BEFORE_BACKOFF) {
            return 0;
        }
        int exponent = Math.min(failures - FAILURES_BEFORE_BACKOFF, 30);
        long seconds = 1L << exponent;
        return (int) Math.min(seconds, MAX_BACKOFF_SECONDS);
    }

    private String key(String username, String remoteAddress) {
        return fingerprint(normalizeUsername(username) + '\0' + normalizeSource(remoteAddress));
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private String normalizeSource(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank()
                ? "unknown"
                : remoteAddress.strip();
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record AttemptState(int failures, Instant blockedUntil, Instant lastActivity) {}
}
