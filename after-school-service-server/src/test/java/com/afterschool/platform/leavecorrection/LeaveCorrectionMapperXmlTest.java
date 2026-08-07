package com.afterschool.platform.leavecorrection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class LeaveCorrectionMapperXmlTest {

    @Test
    void everyGuardianAuthorizationPathRequiresCurrentBinding()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        assertThat(sql(
                        configuration,
                        "lockGuardianStudentBinding",
                        Map.of(
                                "schoolId", 1L,
                                "studentId", 40L,
                                "guardianId", 30L)))
                .contains("FROM student st")
                .contains("sg.guardian_id = ?")
                .contains("sg.is_active = TRUE")
                .endsWith("FOR UPDATE");

        assertThat(sql(
                        configuration,
                        "listGuardianLeaveSessions",
                        Map.of(
                                "studentId", 40L,
                                "guardianId", 30L,
                                "now", LocalDateTime.of(2026, 9, 1, 18, 0))))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        "lockGuardianEligibleEnrollment",
                        Map.of(
                                "schoolId", 1L,
                                "offeringId", 10L,
                                "studentId", 40L,
                                "sessionStartsAt",
                                        LocalDateTime.of(2026, 9, 2, 16, 30))))
                .contains("e.status = 'ENROLLED'")
                .doesNotContain("student_guardian")
                .doesNotContain("JOIN student st")
                .endsWith("FOR UPDATE");
        assertThat(sql(
                        configuration,
                        "countGuardianAttendanceAccess",
                        Map.of("attendanceId", 70L, "guardianId", 30L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        "withdrawLeave",
                        Map.of("id", 50L, "guardianId", 30L, "withdrawnBy", 3L)))
                .doesNotContain("student_guardian")
                .contains("guardian_id = ?");

        Map<String, Object> listParameters = new HashMap<>();
        listParameters.put("schoolId", 1L);
        listParameters.put("teacherId", null);
        listParameters.put("guardianId", 30L);
        listParameters.put("offeringId", null);
        listParameters.put("sessionId", null);
        listParameters.put("status", null);
        assertThat(sql(configuration, "listLeaveRequests", listParameters))
                .contains("JOIN student_guardian current_binding")
                .contains("current_binding.is_active = TRUE");
    }

    @Test
    void leaveApprovalRechecksAnActiveEnrollment()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        assertThat(sql(
                        configuration,
                        "lockReviewableLeaveEnrollment",
                        Map.of(
                                "schoolId", 1L,
                                "offeringId", 10L,
                                "studentId", 40L)))
                .contains("FROM enrollment e")
                .contains("e.status = 'ENROLLED'")
                .doesNotContain("student_guardian")
                .endsWith("FOR UPDATE");
    }

    private String sql(
            Configuration configuration,
            String statementName,
            Map<String, ?> parameters) {
        BoundSql boundSql = configuration
                .getMappedStatement(LeaveCorrectionMapper.class.getName()
                        + "." + statementName)
                .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/leavecorrection/LeaveCorrectionMapper.xml";
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            new XMLMapperBuilder(
                            input,
                            configuration,
                            resource,
                            configuration.getSqlFragments())
                    .parse();
        }
        return configuration;
    }
}
