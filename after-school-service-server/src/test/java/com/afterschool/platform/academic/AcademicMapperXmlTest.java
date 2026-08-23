package com.afterschool.platform.academic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AcademicMapperXmlTest {

    @Test
    void calendarWriteLocksEveryOfferingInTheTenantBeforeInspectingSessions()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".lockCalendarOfferings");

        BoundSql boundSql = statement.getBoundSql(Map.of("schoolId", 9L));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("FROM course_offering")
                .contains("school_id = ?")
                .contains("ORDER BY id")
                .endsWith("FOR UPDATE");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("schoolId");
    }

    @Test
    void parentClosingLocksAssociatedOfferingsByIdInStableOrder() throws Exception {
        Configuration configuration = mapperConfiguration();

        BoundSql termSql = configuration
                .getMappedStatement(AcademicMapper.class.getName()
                        + ".lockTermOfferings")
                .getBoundSql(Map.of("termId", 1L, "schoolId", 9L));
        assertThat(normalize(termSql))
                .contains("FROM course_offering")
                .contains("term_id = ?")
                .contains("school_id = ?")
                .contains("ORDER BY id")
                .endsWith("FOR UPDATE");
        assertThat(termSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("termId", "schoolId");

        BoundSql planSql = configuration
                .getMappedStatement(AcademicMapper.class.getName()
                        + ".lockPlanOfferings")
                .getBoundSql(Map.of("planId", 10L));
        assertThat(normalize(planSql))
                .contains("FROM course_offering")
                .contains("plan_id = ?")
                .contains("ORDER BY id")
                .endsWith("FOR UPDATE");
        assertThat(planSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("planId");
    }

    @Test
    void calendarSessionLockIsScopedBySchoolAndDate() throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".lockCalendarSessions");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "schoolId", 9L,
                "eventDate", LocalDate.of(2026, 9, 2)));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("FROM lesson_session")
                .contains("school_id = ?")
                .contains("session_date = ?")
                .contains("ORDER BY offering_id, id")
                .endsWith("FOR UPDATE");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("schoolId", "eventDate");
        assertThat(statement.getResultMaps().getFirst().getType())
                .isEqualTo(SessionResource.class);
    }

    @Test
    void calendarHistoryCheckCoversEveryIrreversibleSessionWorkflow()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".countCalendarSessionHistory");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "schoolId", 9L,
                "sessionId", 100L));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("FROM attendance")
                .contains("FROM schedule_adjustment")
                .contains("FROM leave_request")
                .contains("FROM supervision_alert")
                .contains("school_id = ?")
                .contains("session_id = ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly(
                        "schoolId", "sessionId",
                        "schoolId", "sessionId",
                        "schoolId", "sessionId",
                        "schoolId", "sessionId");
    }

    @Test
    void calendarCancellationKeepsTenantStatusAndFutureGuardsInTheWrite()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".cancelCalendarSession");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "schoolId", 9L,
                "sessionId", 100L,
                "now", LocalDateTime.of(2026, 7, 31, 18, 0)));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("UPDATE lesson_session")
                .contains("SET status = 'CANCELED'")
                .contains("id = ?")
                .contains("school_id = ?")
                .contains("status = 'SCHEDULED'")
                .contains("TIMESTAMP(session_date, start_time) > ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("sessionId", "schoolId", "now");
    }

    @Test
    void rescheduleLocksAllActiveEnrollmentStudentsInStableIdOrder()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".lockActiveEnrollmentStudentIds");

        BoundSql boundSql = statement.getBoundSql(Map.of("offeringId", 40L));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("FROM student st")
                .contains("JOIN enrollment e ON e.student_id = st.id")
                .contains("e.offering_id = ?")
                .contains("e.status = 'ENROLLED'")
                .contains("ORDER BY st.id")
                .endsWith("FOR UPDATE");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("offeringId");
    }

    @Test
    void studentConflictComparesActualEffectiveSessionsBeforeNoSessionTemplates()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement statement = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".findStudentSessionConflict");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "studentId", 10L,
                "offeringId", 40L,
                "sessionDate", LocalDate.of(2026, 9, 9),
                "startTime", java.time.LocalTime.of(15, 0),
                "endTime", java.time.LocalTime.of(16, 0)));
        String sql = normalize(boundSql);

        assertThat(sql)
                .contains("JOIN lesson_session existing_session")
                .contains("existing_session.status != 'CANCELED'")
                .contains("existing_session.session_date = ?")
                .contains("NOT EXISTS")
                .contains("WEEKDAY(?) + 1")
                .contains("existing.id != ?")
                .contains("e.status = 'ENROLLED'")
                .contains("UNION ALL")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void revertLocksTheLatestActiveAdjustmentAndUsesAStatusGuard()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement latest = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".lockLatestAppliedScheduleAdjustment");
        BoundSql latestSql = latest.getBoundSql(Map.of("sessionId", 100L, "schoolId", 1L));

        assertThat(normalize(latestSql))
                .contains("FROM schedule_adjustment")
                .contains("session_id = ?")
                .contains("school_id = ?")
                .contains("status = 'APPLIED'")
                .contains("ORDER BY id DESC")
                .contains("LIMIT 1")
                .endsWith("FOR UPDATE");

        MappedStatement mark = configuration.getMappedStatement(
                AcademicMapper.class.getName() + ".markScheduleAdjustmentReverted");
        BoundSql markSql = mark.getBoundSql(Map.of("adjustmentId", 900L, "schoolId", 1L));
        assertThat(normalize(markSql))
                .contains("UPDATE schedule_adjustment")
                .contains("SET status = 'REVERTED'")
                .contains("id = ?")
                .contains("school_id = ?")
                .contains("status = 'APPLIED'");
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/academic/AcademicMapper.xml";
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("校历 Mapper XML").isNotNull();
            new XMLMapperBuilder(
                            input,
                            configuration,
                            resource,
                            configuration.getSqlFragments())
                    .parse();
        }
        return configuration;
    }

    private String normalize(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
