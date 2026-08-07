package com.afterschool.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "username")
public class InitialRegulatorBootstrap implements ApplicationRunner {

    private final BootstrapAdminMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public InitialRegulatorBootstrap(
            BootstrapAdminMapper mapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.username}") String username,
            @Value("${app.bootstrap.password:}") String password,
            @Value("${app.bootstrap.display-name:初始监管员}") String displayName) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String normalizedUsername = username.strip();
        if (normalizedUsername.length() < 3 || normalizedUsername.length() > 64) {
            throw new IllegalStateException("APP_BOOTSTRAP_USERNAME 长度必须为 3-64 个字符");
        }
        if (password.length() < 12 || password.length() > 72) {
            throw new IllegalStateException("APP_BOOTSTRAP_PASSWORD 长度必须为 12-72 个字符");
        }
        if (mapper.countUsername(normalizedUsername) == 0) {
            mapper.insertRegulator(
                    normalizedUsername,
                    passwordEncoder.encode(password),
                    displayName.isBlank() ? "初始监管员" : displayName.strip());
        }
    }
}
