package com.afterschool.platform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class EnrollmentMapperXmlTest {

    @Test
    void cancellationTerminatesActiveLeaveRequestsForTheEnrollment()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        BoundSql boundSql = configuration
                .getMappedStatement(EnrollmentMapper.class.getName()
                        + ".withdrawActiveLeavesForEnrollment")
                .getBoundSql(Map.of(
                        "schoolId", 1L,
                        "offeringId", 20L,
                        "studentId", 10L,
                        "withdrawnBy", 11L));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("UPDATE leave_request")
                .contains("status = 'WITHDRAWN'")
                .contains("school_id = ?")
                .contains("offering_id = ?")
                .contains("student_id = ?")
                .contains("status IN ('PENDING', 'APPROVED')")
                .doesNotContain("reviewed_by = NULL")
                .doesNotContain("reviewed_at = NULL");
    }

    @Test
    void reviewedLeaveCanBeWithdrawnWithoutErasingReviewHistory()
            throws Exception {
        String resource =
                "db/migration/V10__preserve_reviewed_leave_withdrawal_history.sql";
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql)
                    .contains("DROP CHECK ck_leave_state")
                    .contains("status = 'WITHDRAWN'")
                    .contains("reviewed_by IS NOT NULL")
                    .contains("reviewed_at IS NOT NULL")
                    .contains("withdrawn_by IS NOT NULL")
                    .contains("withdrawn_at IS NOT NULL");
        }
    }

    @Test
    void actualFirstSessionUsesOnlyNonCanceledLessonSessions() throws Exception {
        Configuration configuration = mapperConfiguration();
        BoundSql boundSql = configuration
                .getMappedStatement(EnrollmentMapper.class.getName()
                        + ".findFirstValidSessionStart")
                .getBoundSql(Map.of("offeringId", 20L));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("MIN(TIMESTAMP(session_date, start_time))")
                .contains("FROM lesson_session")
                .contains("offering_id = ?")
                .contains("status != 'CANCELED'");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("offeringId");
        assertThat(configuration
                        .getMappedStatement(EnrollmentMapper.class.getName()
                                + ".findFirstValidSessionStart")
                        .getResultMaps()
                        .getFirst()
                        .getType())
                .isEqualTo(LocalDateTime.class);
    }

    @Test
    void scheduleConflictPrioritizesActualSessionsAndFallsBackOnlyForNoSessionOfferings()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        BoundSql boundSql = configuration
                .getMappedStatement(EnrollmentMapper.class.getName()
                        + ".countScheduleConflicts")
                .getBoundSql(Map.of("studentId", 10L, "offeringId", 20L));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("JOIN lesson_session candidate_session")
                .contains("candidate_session.session_date = existing_session.session_date")
                .contains("existing_session.status != 'CANCELED'")
                .contains("candidate_session.status != 'CANCELED'")
                .contains("NOT EXISTS")
                .contains("WEEKDAY(existing_session.session_date) + 1")
                .contains("WEEKDAY(candidate_session.session_date) + 1")
                .contains("existing.week_day = candidate.week_day");
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/registration/EnrollmentMapper.xml";
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
