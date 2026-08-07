package com.afterschool.platform.demo;

import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoTimelineRefresher implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(DemoTimelineRefresher.class);

    private static final String ART_OFFERING = "O-DEMO-ART-001";
    private static final Set<String> CURRENT_OFFERINGS = Set.of(
            ART_OFFERING,
            "O-DEMO-TECH-001",
            "O-DEMO-SCI-001");
    private static final Set<String> CURRENT_TERM_CODES =
            Set.of("2026-2027-1", "DEMO-CURRENT");
    private static final Set<Long> SEEDED_FUTURE_SESSION_IDS = Set.of(2L, 3L);
    private static final Map<String, DayOfWeek> OFFERING_DAYS = Map.of(
            ART_OFFERING, DayOfWeek.TUESDAY,
            "O-DEMO-TECH-001", DayOfWeek.THURSDAY,
            "O-DEMO-SCI-001", DayOfWeek.WEDNESDAY);
    private static final Map<String, Long> OFFERING_PLAN_IDS = Map.of(
            ART_OFFERING, 1L,
            "O-DEMO-TECH-001", 1L,
            "O-DEMO-SCI-001", 2L);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DemoTimelineRefresher(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        TermState term = lockTerm();
        lockRows("""
                SELECT id
                FROM school_service_plan
                WHERE id IN (1, 2)
                ORDER BY id
                FOR UPDATE
                """);
        LocalDateTime now = LocalDateTime.now(clock);
        List<OfferingState> offerings = loadCurrentOfferings();
        List<SessionState> sessions = loadCurrentSessions();
        lockRows("""
                SELECT e.id
                FROM enrollment e
                JOIN course_offering o ON o.id = e.offering_id
                WHERE o.offering_code IN (
                    'O-DEMO-ART-001',
                    'O-DEMO-TECH-001',
                    'O-DEMO-SCI-001'
                )
                ORDER BY e.id
                FOR UPDATE
                """);
        lockRows("""
                SELECT lr.id
                FROM leave_request lr
                JOIN course_offering o ON o.id = lr.offering_id
                WHERE o.offering_code IN (
                    'O-DEMO-ART-001',
                    'O-DEMO-TECH-001',
                    'O-DEMO-SCI-001'
                )
                ORDER BY lr.id
                FOR UPDATE
                """);
        lockRows("""
                SELECT sa.id
                FROM schedule_adjustment sa
                JOIN lesson_session ls ON ls.id = sa.session_id
                JOIN course_offering o ON o.id = ls.offering_id
                WHERE o.offering_code IN (
                    'O-DEMO-ART-001',
                    'O-DEMO-TECH-001',
                    'O-DEMO-SCI-001'
                )
                ORDER BY sa.id
                FOR UPDATE
                """);
        lockRows("""
                SELECT id
                FROM school_calendar_event
                WHERE id IN (1, 2, 3)
                ORDER BY id
                FOR UPDATE
                """);
        lockRows("""
                SELECT id
                FROM school_class
                WHERE id IN (1, 2, 3)
                ORDER BY id
                FOR UPDATE
                """);
        if (!isExpired(offerings, sessions, now)) {
            log.info("Demo timeline check completed; no re-anchor was required");
            return;
        }
        if (!isSafeToRefresh(term, offerings, sessions)) {
            log.warn(
                    "Demo timeline has expired but its seeded current workflow "
                            + "was changed; leaving user data untouched. "
                            + "Recreate the demo database before presenting.");
            log.info("Demo timeline check completed; changed data was preserved");
            return;
        }

        DemoTimeline timeline = DemoTimeline.from(now.toLocalDate());
        refreshAcademicContext(timeline);
        refreshOfferings(timeline);
        refreshFutureWorkflow(timeline);
        log.info(
                "Re-anchored the expired demo timeline to {} with future "
                        + "sessions on {} and {}",
                timeline.anchorDate(),
                timeline.firstArtSessionDate(),
                timeline.secondArtSessionDate());
        log.info("Demo timeline check completed after re-anchoring");
    }

    private TermState lockTerm() {
        List<TermState> terms = jdbcTemplate.query(
                """
                SELECT id, term_code, status
                FROM academic_term
                WHERE id = 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new TermState(
                        resultSet.getLong("id"),
                        resultSet.getString("term_code"),
                        resultSet.getString("status")));
        if (terms.size() != 1) {
            throw new IllegalStateException(
                    "The demo profile requires the seeded academic timeline");
        }
        return terms.getFirst();
    }

    private void lockRows(String sql) {
        jdbcTemplate.query(sql, resultSet -> {
            while (resultSet.next()) {
                resultSet.getLong(1);
            }
            return null;
        });
    }

    private List<OfferingState> loadCurrentOfferings() {
        return jdbcTemplate.query(
                """
                SELECT offering_code, term, term_id, plan_id, week_day,
                       start_date, start_time, status
                FROM course_offering
                WHERE offering_code IN (
                    'O-DEMO-ART-001',
                    'O-DEMO-TECH-001',
                    'O-DEMO-SCI-001'
                )
                ORDER BY offering_code
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new OfferingState(
                        resultSet.getString("offering_code"),
                        resultSet.getString("term"),
                        resultSet.getLong("term_id"),
                        resultSet.getLong("plan_id"),
                        resultSet.getInt("week_day"),
                        resultSet.getDate("start_date").toLocalDate(),
                        resultSet.getTime("start_time").toLocalTime(),
                        resultSet.getString("status")));
    }

    private List<SessionState> loadCurrentSessions() {
        return jdbcTemplate.query(
                """
                SELECT ls.id, o.offering_code, ls.session_date,
                       ls.start_time, ls.status
                FROM lesson_session ls
                JOIN course_offering o ON o.id = ls.offering_id
                WHERE o.offering_code IN (
                    'O-DEMO-ART-001',
                    'O-DEMO-TECH-001',
                    'O-DEMO-SCI-001'
                )
                ORDER BY ls.id
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new SessionState(
                        resultSet.getLong("id"),
                        resultSet.getString("offering_code"),
                        resultSet.getDate("session_date").toLocalDate(),
                        resultSet.getTime("start_time").toLocalTime(),
                        resultSet.getString("status")));
    }

    private boolean isExpired(
            List<OfferingState> offerings,
            List<SessionState> sessions,
            LocalDateTime now) {
        if (offerings.size() != CURRENT_OFFERINGS.size()
                || sessions.size() != SEEDED_FUTURE_SESSION_IDS.size()) {
            return true;
        }
        return offerings.stream().anyMatch(offering ->
                        !now.isBefore(offering.firstSessionStart()))
                || sessions.stream().anyMatch(session ->
                        !now.isBefore(session.sessionStart()));
    }

    private boolean isSafeToRefresh(
            TermState term,
            List<OfferingState> offerings,
            List<SessionState> sessions) {
        boolean termMatches = term.id() == 1L
                && CURRENT_TERM_CODES.contains(term.code())
                && "ACTIVE".equals(term.status());
        boolean offeringsMatch = offerings.size() == CURRENT_OFFERINGS.size()
                && offerings.stream().allMatch(offering ->
                        CURRENT_OFFERINGS.contains(offering.code())
                                && CURRENT_TERM_CODES.contains(
                                        offering.termCode())
                                && offering.termId() == 1L
                                && OFFERING_PLAN_IDS.get(offering.code())
                                        == offering.planId()
                                && OFFERING_DAYS.get(offering.code())
                                        == DayOfWeek.of(offering.weekDay())
                                && "PUBLISHED".equals(offering.status()));
        boolean sessionsMatch = sessions.size() == SEEDED_FUTURE_SESSION_IDS.size()
                && sessions.stream().allMatch(session ->
                        SEEDED_FUTURE_SESSION_IDS.contains(session.id())
                                && ART_OFFERING.equals(session.offeringCode())
                                && "SCHEDULED".equals(session.status()));
        return termMatches
                && offeringsMatch
                && sessionsMatch
                && count("""
                        SELECT COUNT(*)
                        FROM school_class
                        WHERE (id = 1 AND school_id = 1 AND status = 'ACTIVE')
                           OR (id = 2 AND school_id = 1 AND status = 'ACTIVE')
                           OR (id = 3 AND school_id = 2 AND status = 'ACTIVE')
                        """) == 3
                && count("""
                        SELECT COUNT(*)
                        FROM enrollment e
                        JOIN course_offering o ON o.id = e.offering_id
                        WHERE o.offering_code IN (
                            'O-DEMO-ART-001',
                            'O-DEMO-TECH-001',
                            'O-DEMO-SCI-001'
                        )
                          AND e.id = 1
                          AND e.student_id = 2
                          AND e.status = 'ENROLLED'
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM enrollment e
                        JOIN course_offering o ON o.id = e.offering_id
                        WHERE o.offering_code IN (
                            'O-DEMO-ART-001',
                            'O-DEMO-TECH-001',
                            'O-DEMO-SCI-001'
                        )
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM leave_request
                        WHERE id = 1
                          AND session_id = 2
                          AND status = 'APPROVED'
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM leave_request lr
                        JOIN course_offering o ON o.id = lr.offering_id
                        WHERE o.offering_code IN (
                            'O-DEMO-ART-001',
                            'O-DEMO-TECH-001',
                            'O-DEMO-SCI-001'
                        )
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM schedule_adjustment
                        WHERE id = 1
                          AND session_id = 3
                          AND status = 'APPLIED'
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM schedule_adjustment sa
                        JOIN lesson_session ls ON ls.id = sa.session_id
                        JOIN course_offering o ON o.id = ls.offering_id
                        WHERE o.offering_code IN (
                            'O-DEMO-ART-001',
                            'O-DEMO-TECH-001',
                            'O-DEMO-SCI-001'
                        )
                        """) == 1
                && count("""
                        SELECT COUNT(*)
                        FROM school_service_plan
                        WHERE term_id = 1
                          AND (
                              (
                                  id = 1
                                  AND school_id = 1
                                  AND plan_code IN (
                                      'PLAN-DEMO001-2026-1',
                                      'PLAN-DEMO001-CURRENT'
                                  )
                                  AND status = 'ACTIVE'
                              )
                              OR (
                                  id = 2
                                  AND school_id = 2
                                  AND plan_code IN (
                                      'PLAN-DEMO002-2026-1',
                                      'PLAN-DEMO002-CURRENT'
                                  )
                                  AND status = 'FILED'
                              )
                          )
                        """) == 2
                && count("""
                        SELECT COUNT(*)
                        FROM school_calendar_event
                        WHERE term_id = 1
                          AND (
                              (
                                  id = 1
                                  AND school_id = 1
                                  AND day_type = 'TEACHING_DAY'
                              )
                              OR (
                                  id = 2
                                  AND school_id = 1
                                  AND day_type = 'HOLIDAY'
                              )
                              OR (
                                  id = 3
                                  AND school_id = 2
                                  AND day_type = 'HOLIDAY'
                              )
                          )
                        """) == 3;
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private void refreshAcademicContext(DemoTimeline timeline) {
        requireUpdated(
                "the academic term",
                jdbcTemplate.update(
                """
                UPDATE academic_term
                SET term_code = 'DEMO-CURRENT',
                    term_name = ?,
                    start_date = ?,
                    end_date = ?
                WHERE id = 1
                  AND status = 'ACTIVE'
                """,
                timeline.termName(),
                Date.valueOf(timeline.termStartDate()),
                Date.valueOf(timeline.termEndDate())),
                1);
        requireUpdated(
                "the seeded classes",
                jdbcTemplate.update(
                """
                UPDATE school_class
                SET school_year = ?
                WHERE id IN (1, 2, 3)
                  AND status = 'ACTIVE'
                """,
                timeline.schoolYear()),
                3);
        requireUpdated(
                "the first service plan",
                jdbcTemplate.update(
                """
                UPDATE school_service_plan
                SET plan_code = 'PLAN-DEMO001-CURRENT',
                    plan_name = '示范实验学校当前课后服务计划',
                    submitted_at = ?,
                    filed_at = ?,
                    activated_at = ?
                WHERE id = 1
                  AND status = 'ACTIVE'
                """,
                timeline.atTime(-25, 9, 0),
                timeline.atTime(-24, 10, 30),
                timeline.atTime(-20, 8, 0)),
                1);
        requireUpdated(
                "the second service plan",
                jdbcTemplate.update(
                """
                UPDATE school_service_plan
                SET plan_code = 'PLAN-DEMO002-CURRENT',
                    plan_name = '启航外国语学校当前课后服务计划',
                    submitted_at = ?,
                    filed_at = ?
                WHERE id = 2
                  AND status = 'FILED'
                """,
                timeline.atTime(-24, 9, 20),
                timeline.atTime(-23, 14, 0)),
                1);
        requireUpdated(
                "the teaching-day calendar event",
                jdbcTemplate.update(
                        """
                        UPDATE school_calendar_event
                        SET event_date = ?
                        WHERE id = 1
                          AND day_type = 'TEACHING_DAY'
                        """,
                        Date.valueOf(timeline.offeringStartDate())),
                1);
        requireUpdated(
                "the holiday calendar events",
                jdbcTemplate.update(
                        """
                        UPDATE school_calendar_event
                        SET event_date = ?
                        WHERE id IN (2, 3)
                          AND day_type = 'HOLIDAY'
                        """,
                        Date.valueOf(timeline.holidayDate())),
                2);
    }

    private void refreshOfferings(DemoTimeline timeline) {
        for (Map.Entry<String, DayOfWeek> offering : OFFERING_DAYS.entrySet()) {
            LocalDate firstSessionDate =
                    timeline.firstSessionDate(offering.getValue());
            requireUpdated(
                    "course offering " + offering.getKey(),
                    jdbcTemplate.update(
                    """
                    UPDATE course_offering
                    SET term = 'DEMO-CURRENT',
                        start_date = ?,
                        end_date = ?,
                        enrollment_start = ?,
                        enrollment_end = ?
                    WHERE offering_code = ?
                      AND status = 'PUBLISHED'
                    """,
                    Date.valueOf(timeline.offeringStartDate()),
                    Date.valueOf(timeline.offeringEndDate()),
                    timeline.atTime(-30, 0, 0),
                    LocalDateTime.of(firstSessionDate, LocalTime.of(16, 29, 59)),
                    offering.getKey()),
                    1);
        }
        requireUpdated(
                "the seeded enrollment",
                jdbcTemplate.update(
                        """
                        UPDATE enrollment
                        SET enrolled_at = ?
                        WHERE id = 1
                          AND status = 'ENROLLED'
                        """,
                        timeline.atTime(-3, 9, 0)),
                1);
    }

    private void refreshFutureWorkflow(DemoTimeline timeline) {
        requireUpdated(
                "the first future session",
                jdbcTemplate.update(
                """
                UPDATE lesson_session
                SET session_date = ?
                WHERE id = 2
                  AND status = 'SCHEDULED'
                """,
                Date.valueOf(timeline.firstArtSessionDate())),
                1);
        requireUpdated(
                "the second future session",
                jdbcTemplate.update(
                """
                UPDATE lesson_session
                SET session_date = ?
                WHERE id = 3
                  AND status = 'SCHEDULED'
                """,
                Date.valueOf(timeline.secondArtSessionDate())),
                1);
        requireUpdated(
                "the seeded leave request",
                jdbcTemplate.update(
                """
                UPDATE leave_request
                SET submitted_at = ?,
                    reviewed_at = ?
                WHERE id = 1
                  AND status = 'APPROVED'
                """,
                timeline.atTime(-2, 19, 20),
                timeline.atTime(-1, 8, 30)),
                1);
        requireUpdated(
                "the seeded schedule adjustment",
                jdbcTemplate.update(
                """
                UPDATE schedule_adjustment
                SET original_session_date = ?,
                    adjusted_session_date = ?,
                    applied_at = ?
                WHERE id = 1
                  AND status = 'APPLIED'
                """,
                Date.valueOf(timeline.holidayDate()),
                Date.valueOf(timeline.secondArtSessionDate()),
                timeline.atTime(-1, 10, 0)),
                1);
    }

    private void requireUpdated(String target, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Demo timeline refresh could not update " + target);
        }
    }

    static record TermState(long id, String code, String status) {}

    static record OfferingState(
            String code,
            String termCode,
            long termId,
            long planId,
            int weekDay,
            LocalDate startDate,
            LocalTime startTime,
            String status) {

        LocalDateTime firstSessionStart() {
            DayOfWeek targetDay = DayOfWeek.of(weekDay);
            LocalDate firstDate =
                    startDate.with(TemporalAdjusters.nextOrSame(targetDay));
            return LocalDateTime.of(firstDate, startTime);
        }
    }

    static record SessionState(
            long id,
            String offeringCode,
            LocalDate sessionDate,
            LocalTime startTime,
            String status) {

        LocalDateTime sessionStart() {
            return LocalDateTime.of(sessionDate, startTime);
        }
    }

    static record DemoTimeline(
            LocalDate anchorDate,
            String schoolYear,
            String termName,
            LocalDate termStartDate,
            LocalDate termEndDate,
            LocalDate offeringStartDate,
            LocalDate offeringEndDate,
            LocalDate firstArtSessionDate,
            LocalDate secondArtSessionDate,
            LocalDate holidayDate) {

        static DemoTimeline from(LocalDate anchorDate) {
            int schoolYearStart = anchorDate.getMonthValue() >= 7
                    ? anchorDate.getYear()
                    : anchorDate.getYear() - 1;
            String schoolYear = schoolYearStart + "-" + (schoolYearStart + 1);
            LocalDate offeringStartDate = anchorDate.plusDays(7);
            LocalDate firstArtSessionDate = offeringStartDate.with(
                    TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
            LocalDate secondArtSessionDate = firstArtSessionDate.plusWeeks(1);
            return new DemoTimeline(
                    anchorDate,
                    schoolYear,
                    schoolYear.replace("-", "—") + " 学年当前演示学期",
                    anchorDate.minusDays(30),
                    anchorDate.plusDays(180),
                    offeringStartDate,
                    anchorDate.plusDays(150),
                    firstArtSessionDate,
                    secondArtSessionDate,
                    secondArtSessionDate.plusWeeks(1));
        }

        LocalDate firstSessionDate(DayOfWeek dayOfWeek) {
            return offeringStartDate.with(
                    TemporalAdjusters.nextOrSame(dayOfWeek));
        }

        LocalDateTime atTime(int dayOffset, int hour, int minute) {
            return anchorDate
                    .plusDays(dayOffset)
                    .atTime(hour, minute);
        }
    }
}
