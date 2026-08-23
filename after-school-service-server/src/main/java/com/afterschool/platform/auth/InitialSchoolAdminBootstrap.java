package com.afterschool.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        prefix = "app.bootstrap",
        name = "enabled",
        havingValue = "true")
public class InitialSchoolAdminBootstrap implements ApplicationRunner {

    private final BootstrapAdminMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;
    private final String schoolCode;
    private final String schoolName;
    private final String districtCode;

    public InitialSchoolAdminBootstrap(
            BootstrapAdminMapper mapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.username:}") String username,
            @Value("${app.bootstrap.password:}") String password,
            @Value("${app.bootstrap.display-name:初始教务管理员}") String displayName,
            @Value("${app.bootstrap.school-code:}") String schoolCode,
            @Value("${app.bootstrap.school-name:}") String schoolName,
            @Value("${app.bootstrap.district-code:}") String districtCode) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.schoolCode = schoolCode;
        this.schoolName = schoolName;
        this.districtCode = districtCode;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (mapper.countSchoolAdmins() > 0) {
            return;
        }
        if (mapper.countSchools() > 0) {
            throw new IllegalStateException(
                    "已有学校但没有教务管理员，禁止自动初始化；请按恢复流程人工处理");
        }
        String normalizedUsername = required(username, "APP_BOOTSTRAP_USERNAME", 3, 64);
        String normalizedDisplayName = required(
                displayName, "APP_BOOTSTRAP_DISPLAY_NAME", 1, 64);
        String normalizedSchoolCode = required(
                schoolCode, "APP_BOOTSTRAP_SCHOOL_CODE", 1, 32);
        String normalizedSchoolName = required(
                schoolName, "APP_BOOTSTRAP_SCHOOL_NAME", 1, 128);
        String normalizedDistrictCode = required(
                districtCode, "APP_BOOTSTRAP_DISTRICT_CODE", 1, 32);
        if (password.length() < 12 || password.length() > 72) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_PASSWORD 长度必须为 12-72 个字符");
        }
        if (mapper.countUsername(normalizedUsername) > 0) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_USERNAME 已被非教务账号占用");
        }
        if (mapper.insertSchool(
                        normalizedSchoolCode,
                        normalizedSchoolName,
                        normalizedDistrictCode)
                != 1) {
            throw new IllegalStateException("初始学校创建失败");
        }
        Long schoolId = mapper.findSchoolIdByCode(normalizedSchoolCode);
        if (schoolId == null
                || mapper.insertSchoolAdmin(
                                schoolId,
                                normalizedUsername,
                                passwordEncoder.encode(password),
                                normalizedDisplayName)
                        != 1) {
            throw new IllegalStateException("初始教务管理员创建失败");
        }
    }

    private String required(String value, String name, int min, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalStateException(
                    name + " 长度必须为 " + min + "-" + max + " 个字符");
        }
        return normalized;
    }
}
