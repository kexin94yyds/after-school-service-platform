package com.afterschool.platform.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterschool.platform.evaluation.EvaluationMapper;
import com.afterschool.platform.registration.EnrollmentMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class GuardianBindingAuthorizationMapperXmlTest {

    @Test
    void migrationAddsCurrentAuthorizationStateWithoutRemovingHistoricRows()
            throws Exception {
        String resource =
                "db/migration/V9__separate_current_guardian_authorization.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("监护关系授权迁移").isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql)
                    .contains("ALTER TABLE student_guardian")
                    .contains("ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE")
                    .doesNotContain("DELETE");
        }
    }

    @Test
    void administratorUpdateReactivatesSelectedBindingsAndSoftRevokesOthers()
            throws Exception {
        Configuration configuration = mapperConfiguration(
                "mapper/people/PeopleMapper.xml");

        assertThat(sql(
                        configuration,
                        PeopleMapper.class,
                        "insertGuardianBinding",
                        Map.of(
                                "studentId", 5L,
                                "guardianId", 13L,
                                "relationship", "母亲",
                                "primary", true)))
                .contains("is_active")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("is_active = TRUE");
        assertThat(sql(
                        configuration,
                        PeopleMapper.class,
                        "deactivateGuardianBindingsExcept",
                        Map.of(
                                "guardianId", 13L,
                                "studentIds", List.of(5L))))
                .contains("UPDATE student_guardian")
                .contains("SET is_active = FALSE")
                .contains("student_id NOT IN")
                .doesNotContain("DELETE");
        assertThat(sql(
                        configuration,
                        PeopleMapper.class,
                        "listGuardians",
                        Map.of("schoolId", 1L)))
                .contains("sg.is_active = TRUE");
    }

    @Test
    void enrollmentAuthorizationOnlyUsesCurrentGuardianBindings()
            throws Exception {
        Configuration configuration = mapperConfiguration(
                "mapper/registration/EnrollmentMapper.xml");
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "guardianStudents",
                        Map.of("guardianId", 13L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "findGuardianStudent",
                        Map.of("studentId", 5L, "guardianId", 13L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "lockGuardianStudent",
                        Map.of("studentId", 5L, "guardianId", 13L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "findScopedEnrollment",
                        Map.of("id", 21L, "guardianId", 13L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "lockScopedEnrollment",
                        Map.of("id", 21L, "guardianId", 13L)))
                .contains("sg.is_active = TRUE");

        Map<String, Object> listParameters = new HashMap<>();
        listParameters.put("schoolId", 1L);
        listParameters.put("teacherId", null);
        listParameters.put("guardianId", 13L);
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "listEnrollments",
                        listParameters))
                .contains("JOIN student_guardian current_binding")
                .contains("current_binding.is_active = TRUE");

        Map<String, Object> cancelParameters = new HashMap<>();
        cancelParameters.put("id", 21L);
        cancelParameters.put("guardianId", 13L);
        cancelParameters.put("schoolId", null);
        cancelParameters.put("canceledBy", 7L);
        assertThat(sql(
                        configuration,
                        EnrollmentMapper.class,
                        "cancelEnrollment",
                        cancelParameters))
                .contains("EXISTS")
                .contains("sg.is_active = TRUE");
    }

    @Test
    void evaluationAuthorizationOnlyUsesCurrentGuardianBindings()
            throws Exception {
        Configuration configuration = mapperConfiguration(
                "mapper/evaluation/EvaluationMapper.xml");
        assertThat(sql(
                        configuration,
                        EvaluationMapper.class,
                        "lockEligibility",
                        Map.of(
                                "studentId", 5L,
                                "offeringId", 8L,
                                "guardianId", 13L,
                                "schoolId", 1L)))
                .contains("sg.is_active = TRUE");
        assertThat(sql(
                        configuration,
                        EvaluationMapper.class,
                        "listGuardianEvaluations",
                        Map.of("guardianId", 13L, "schoolId", 1L)))
                .contains("sg.is_active = TRUE");
    }

    private String sql(
            Configuration configuration,
            Class<?> mapperType,
            String statementName,
            Map<String, ?> parameters) {
        BoundSql boundSql = configuration
                .getMappedStatement(mapperType.getName() + "." + statementName)
                .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private Configuration mapperConfiguration(String resource) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
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
