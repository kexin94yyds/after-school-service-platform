package com.afterschool.platform.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class TeachingMapperXmlTest {

    @Test
    void closedCalendarDateQueryIsScopedBySchoolTermTypeAndOfferingRange()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        MappedStatement statement = configuration.getMappedStatement(
                TeachingMapper.class.getName() + ".listClosedCalendarDates");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "schoolId", 1L,
                "termId", 7L,
                "startDate", LocalDate.of(2026, 9, 1),
                "endDate", LocalDate.of(2026, 9, 30)));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("FROM school_calendar_event")
                .contains("school_id = ?")
                .contains("term_id = ?")
                .contains("day_type IN ('HOLIDAY', 'SUSPENDED')")
                .contains("event_date BETWEEN ? AND ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly(
                        "schoolId", "termId", "startDate", "endDate");
        assertThat(statement.getResultMaps()).hasSize(1);
        assertThat(statement.getResultMaps().getFirst().getType())
                .isEqualTo(LocalDate.class);
    }

    @Test
    void appliedRescheduleOriginalDateQueryIncludesEveryHopForTheOffering()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        MappedStatement statement = configuration.getMappedStatement(
                TeachingMapper.class.getName() + ".listAppliedRescheduleOriginalDates");
        BoundSql boundSql = statement.getBoundSql(Map.of("offeringId", 10L));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("SELECT DISTINCT a.original_session_date")
                .contains("FROM schedule_adjustment a")
                .contains("JOIN lesson_session ls")
                .contains("ls.id = a.session_id")
                .contains("ls.school_id = a.school_id")
                .contains("ls.offering_id = ?")
                .contains("a.status = 'APPLIED'")
                .doesNotContain("LIMIT");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("offeringId");
        assertThat(statement.getResultMaps()).hasSize(1);
        assertThat(statement.getResultMaps().getFirst().getType())
                .isEqualTo(LocalDate.class);
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/teaching/TeachingMapper.xml";
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("教学 Mapper XML").isNotNull();
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
