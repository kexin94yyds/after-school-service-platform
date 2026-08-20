package com.afterschool.platform.report;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.common.ApiException;
import com.afterschool.platform.common.excel.SimpleXlsx;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private static final Set<String> OFFERING_STATUSES = Set.of(
            "DRAFT", "PUBLISHED", "CLOSED", "FINISHED", "CANCELED");
    private static final List<String> PERFORMANCE_KEYS = List.of(
            "schoolName",
            "termName",
            "category",
            "courseName",
            "offeringCode",
            "teacherName",
            "status",
            "activeEnrollmentCount",
            "sessionCount",
            "completedSessions",
            "completedHours",
            "attendanceRate",
            "evaluationCount",
            "averageRating",
            "satisfactionRate",
            "averageTeacherRating",
            "teacherSatisfactionRate");
    private static final List<String> PERFORMANCE_HEADERS = List.of(
            "学校",
            "学期",
            "课程类别",
            "课程名称",
            "开课编号",
            "授课教师",
            "开课状态",
            "有效报名数",
            "课次数",
            "已完成课次数",
            "已完成课时",
            "出勤率(%)",
            "评价数",
            "课程平均评分",
            "课程满意度(%)",
            "教师平均评分",
            "教师满意度(%)");

    private final ReportMapper mapper;
    private final CurrentUser currentUser;

    public ReportService(ReportMapper mapper, CurrentUser currentUser) {
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    public Map<String, Object> overview(Long requestedSchoolId) {
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        Map<String, Object> response = new LinkedHashMap<>(mapper.overview(schoolId));
        response.put("schools", mapper.schoolBreakdown(schoolId));
        response.put("categories", mapper.categoryBreakdown(schoolId));
        response.put("attendance", mapper.attendanceBreakdown(schoolId));
        response.put("teacherHours", mapper.teacherHours(schoolId));
        response.put("satisfaction", mapper.satisfactionSummary(schoolId));
        return response;
    }

    public List<Map<String, Object>> teacherHours(Long requestedSchoolId) {
        return mapper.teacherHours(currentUser.optionalSchoolScope(requestedSchoolId));
    }

    public List<Map<String, Object>> coursePerformance(
            Long requestedSchoolId,
            Long termId,
            LocalDate fromDate,
            LocalDate toDate,
            String category,
            String requestedStatus) {
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        if (termId != null && termId <= 0) {
            throw ApiException.badRequest("INVALID_TERM", "学期编号必须大于零");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw ApiException.badRequest(
                    "INVALID_DATE_RANGE",
                    "开始日期不能晚于结束日期");
        }
        String status = normalizeOptional(requestedStatus);
        if (status != null) {
            status = status.toUpperCase();
            if (!OFFERING_STATUSES.contains(status)) {
                throw ApiException.badRequest(
                        "INVALID_OFFERING_STATUS",
                        "开课状态不正确");
            }
        }
        return mapper.coursePerformance(
                schoolId,
                termId,
                fromDate,
                toDate,
                normalizeOptional(category),
                status);
    }

    public byte[] coursePerformanceCsv(
            Long requestedSchoolId,
            Long termId,
            LocalDate fromDate,
            LocalDate toDate,
            String category,
            String status) {
        List<Map<String, Object>> rows = coursePerformance(
                requestedSchoolId,
                termId,
                fromDate,
                toDate,
                category,
                status);
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, PERFORMANCE_HEADERS);
        for (Map<String, Object> row : rows) {
            appendCsvRow(
                    csv,
                    PERFORMANCE_KEYS.stream()
                            .map(row::get)
                            .toList());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] coursePerformanceXlsx(
            Long requestedSchoolId,
            Long termId,
            LocalDate fromDate,
            LocalDate toDate,
            String category,
            String status) {
        List<Map<String, Object>> rows = coursePerformance(
                requestedSchoolId, termId, fromDate, toDate, category, status);
        List<? extends List<?>> data = rows.stream()
                .map(row -> PERFORMANCE_KEYS.stream()
                        .map(key -> xlsxValue(row.get(key)))
                        .toList())
                .toList();
        return SimpleXlsx.write("课程绩效", PERFORMANCE_HEADERS, data);
    }

    public List<Map<String, Object>> rectifications(
            Long requestedSchoolId,
            LocalDate detectedFrom,
            LocalDate detectedTo) {
        if (detectedFrom != null && detectedTo != null
                && detectedFrom.isAfter(detectedTo)) {
            throw ApiException.badRequest(
                    "INVALID_DATE_RANGE", "开始日期不能晚于结束日期");
        }
        return mapper.rectificationBreakdown(
                currentUser.optionalSchoolScope(requestedSchoolId),
                detectedFrom,
                detectedTo);
    }

    public byte[] rectificationsXlsx(
            Long requestedSchoolId,
            LocalDate detectedFrom,
            LocalDate detectedTo) {
        List<String> headers = List.of(
                "学校",
                "预警类型",
                "违规等级",
                "整改状态",
                "事项数",
                "逾期数",
                "平均闭环小时");
        List<? extends List<?>> data = rectifications(
                        requestedSchoolId, detectedFrom, detectedTo)
                .stream()
                .map(row -> List.of(
                        xlsxValue(row.get("schoolName")),
                        xlsxValue(row.get("alertType")),
                        xlsxValue(row.get("severity")),
                        xlsxValue(row.get("status")),
                        xlsxValue(row.get("alertCount")),
                        xlsxValue(row.get("overdueCount")),
                        xlsxValue(row.get("averageCloseHours"))))
                .toList();
        return SimpleXlsx.write("违规整改统计", headers, data);
    }

    private void appendCsvRow(
            StringBuilder target, List<?> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                target.append(',');
            }
            String value = cells.get(index) == null
                    ? ""
                    : cells.get(index).toString();
            if (requiresFormulaEscape(value)) {
                value = "'" + value;
            }
            target.append('"')
                    .append(value.replace("\"", "\"\""))
                    .append('"');
        }
        target.append("\r\n");
    }

    private boolean requiresFormulaEscape(String value) {
        int index = 0;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character)
                    && !Character.isISOControl(character)) {
                break;
            }
            index++;
        }
        return index < value.length()
                && "=+-@".indexOf(value.charAt(index)) >= 0;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Object xlsxValue(Object value) {
        return value == null ? "" : value;
    }
}
