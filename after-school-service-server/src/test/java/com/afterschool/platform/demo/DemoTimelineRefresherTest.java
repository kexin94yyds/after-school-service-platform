package com.afterschool.platform.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DemoTimelineRefresherTest {

    @Test
    void derivesAStableFutureWorkflowFromTheShanghaiAnchorDate() {
        DemoTimelineRefresher.DemoTimeline timeline =
                DemoTimelineRefresher.DemoTimeline.from(
                        LocalDate.of(2026, 10, 1));

        assertThat(timeline.schoolYear()).isEqualTo("2026-2027");
        assertThat(timeline.termStartDate()).isEqualTo("2026-09-01");
        assertThat(timeline.termEndDate()).isEqualTo("2027-03-30");
        assertThat(timeline.offeringStartDate()).isEqualTo("2026-10-08");
        assertThat(timeline.firstArtSessionDate()).isEqualTo("2026-10-13");
        assertThat(timeline.secondArtSessionDate()).isEqualTo("2026-10-20");
        assertThat(timeline.holidayDate()).isEqualTo("2026-10-27");
        assertThat(timeline.firstSessionDate(DayOfWeek.WEDNESDAY))
                .isEqualTo("2026-10-14");
        assertThat(timeline.firstSessionDate(DayOfWeek.THURSDAY))
                .isEqualTo("2026-10-08");
        assertThat(timeline.atTime(-1, 8, 30))
                .isEqualTo(LocalDateTime.of(2026, 9, 30, 8, 30));
        assertThat(timeline.atTime(-3, 9, 0))
                .isBefore(timeline.atTime(-2, 19, 20));
        assertThat(timeline.atTime(-2, 19, 20))
                .isBefore(timeline.atTime(-1, 8, 30));
        assertThat(timeline.atTime(-1, 8, 30))
                .isBefore(timeline.firstArtSessionDate().atStartOfDay());
    }

    @Test
    void keepsTimelineRelationshipsAcrossAYearBoundary() {
        DemoTimelineRefresher.DemoTimeline timeline =
                DemoTimelineRefresher.DemoTimeline.from(
                        LocalDate.of(2030, 12, 29));

        assertThat(timeline.schoolYear()).isEqualTo("2030-2031");
        assertThat(timeline.offeringStartDate()).isEqualTo("2031-01-05");
        assertThat(timeline.firstArtSessionDate()).isEqualTo("2031-01-07");
        assertThat(timeline.secondArtSessionDate()).isEqualTo("2031-01-14");
        assertThat(timeline.holidayDate()).isEqualTo("2031-01-21");
        assertThat(timeline.firstArtSessionDate())
                .isAfter(timeline.anchorDate().plusDays(6))
                .isBefore(timeline.termEndDate());
    }

    @Test
    void computesTheFirstRealClassFromOfferingWeekday() {
        DemoTimelineRefresher.OfferingState offering =
                new DemoTimelineRefresher.OfferingState(
                        1L,
                        "O-DEMO-TECH-001",
                        "DEMO-CURRENT",
                        1L,
                        1L,
                        1L,
                        DayOfWeek.THURSDAY.getValue(),
                        LocalDate.of(2026, 10, 5),
                        java.time.LocalTime.of(16, 30),
                        "PUBLISHED");

        assertThat(offering.firstSessionStart())
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 16, 30));
    }
}
