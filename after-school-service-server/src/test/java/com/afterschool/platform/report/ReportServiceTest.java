package com.afterschool.platform.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private ReportMapper mapper;
    private CurrentUser currentUser;
    private ReportService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ReportMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new ReportService(mapper, currentUser);
    }

    @Test
    void overviewKeepsLegacySectionsAndAddsSatisfaction() {
        when(currentUser.optionalSchoolScope(null)).thenReturn(1L);
        when(mapper.overview(1L))
                .thenReturn(Map.of("schoolCount", 1L));
        when(mapper.schoolBreakdown(1L)).thenReturn(List.of());
        when(mapper.categoryBreakdown(1L)).thenReturn(List.of());
        when(mapper.attendanceBreakdown(1L)).thenReturn(List.of());
        when(mapper.teacherHours(1L)).thenReturn(List.of());
        when(mapper.satisfactionSummary(1L))
                .thenReturn(Map.of(
                        "evaluationCount", 2L,
                        "satisfactionRate", 100));

        Map<String, Object> overview = service.overview(null);

        assertThat(overview)
                .containsKeys(
                        "schoolCount",
                        "schools",
                        "categories",
                        "attendance",
                        "teacherHours",
                        "satisfaction");
    }

    @Test
    void csvIsUtf8BomPrefixedAndNeutralizesSpreadsheetFormulaCells() {
        when(currentUser.optionalSchoolScope(1L)).thenReturn(1L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schoolName", " \t=HYPERLINK(\"bad\")");
        row.put("termName", "2026 秋季");
        row.put("category", "体育");
        row.put("courseName", "篮球");
        row.put("offeringCode", "O-1");
        row.put("teacherName", "教师");
        row.put("status", "FINISHED");
        row.put("activeEnrollmentCount", 10);
        row.put("sessionCount", 8);
        row.put("completedSessions", 8);
        row.put("completedHours", 8);
        row.put("attendanceRate", 95);
        row.put("evaluationCount", 3);
        row.put("averageRating", 4.7);
        row.put("satisfactionRate", 100);
        when(mapper.coursePerformance(
                        1L,
                        2L,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 31),
                        "体育",
                        "FINISHED"))
                .thenReturn(List.of(row));

        byte[] result = service.coursePerformanceCsv(
                1L,
                2L,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                "体育",
                "finished");
        String csv = new String(result, StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF\"学校\"");
        assertThat(csv).contains("\"' \t=HYPERLINK(\"\"bad\"\")\"");
    }
}
