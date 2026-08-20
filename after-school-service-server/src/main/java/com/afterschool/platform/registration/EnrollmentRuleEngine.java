package com.afterschool.platform.registration;

import com.afterschool.platform.common.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class EnrollmentRuleEngine {

    public void validate(
            EnrollmentStudent student,
            EnrollmentOffering offering,
            EnrollmentState existing,
            boolean hasScheduleConflict,
            LocalDateTime now) {
        validate(
                student,
                offering,
                existing,
                hasScheduleConflict,
                templateFirstSessionStart(offering),
                now);
    }

    /**
     * Validates an enrollment against the effective first lesson.  Callers must
     * supply the first non-cancelled {@code lesson_session} when one exists;
     * {@link #templateFirstSessionStart(EnrollmentOffering)} is only the
     * no-session fallback.
     */
    public void validate(
            EnrollmentStudent student,
            EnrollmentOffering offering,
            EnrollmentState existing,
            boolean hasScheduleConflict,
            LocalDateTime firstSessionStart,
            LocalDateTime now) {
        if (!"ACTIVE".equals(student.getStatus())) {
            throw ApiException.conflict("STUDENT_INACTIVE", "学生状态不可报名");
        }
        if (student.getSchoolId() != offering.getSchoolId()) {
            throw ApiException.forbidden("学生与开班不属于同一学校");
        }
        if (!"PUBLISHED".equals(offering.getStatus())) {
            throw ApiException.conflict("OFFERING_NOT_OPEN", "该开班当前不可报名");
        }
        if (!"ACTIVE".equals(offering.getCourseStatus())) {
            throw ApiException.conflict("COURSE_INACTIVE", "该课程已停用，不能继续报名");
        }
        if (offering.getTermStatus() == null
                || offering.getPlanStatus() == null) {
            throw ApiException.conflict(
                    "OFFERING_NOT_FILED",
                    "开班未关联已备案服务计划，不能报名");
        }
        if (isClosedStatus(offering.getTermStatus())) {
            throw ApiException.conflict(
                    "TERM_CLOSED", "开班所属学期已结束或归档，不能报名");
        }
        if (isClosedStatus(offering.getPlanStatus())) {
            throw ApiException.conflict(
                    "SERVICE_PLAN_CLOSED", "开班所属服务计划已结束或归档，不能报名");
        }
        if (!"FILED".equals(offering.getPlanStatus())
                && !"ACTIVE".equals(offering.getPlanStatus())) {
            throw ApiException.conflict(
                    "SERVICE_PLAN_NOT_FILED",
                    "开班所属服务计划尚未完成备案，不能报名");
        }
        if (now.isBefore(offering.getEnrollmentStart()) || now.isAfter(offering.getEnrollmentEnd())) {
            throw ApiException.conflict("OUTSIDE_ENROLLMENT_WINDOW", "当前不在报名开放时间内");
        }
        if (!now.isBefore(firstSessionStart)) {
            throw ApiException.conflict(
                    "ENROLLMENT_CLOSED_AFTER_START",
                    "首节课开始后不能再报名");
        }
        if (student.getGrade() < offering.getTargetGradeMin()
                || student.getGrade() > offering.getTargetGradeMax()) {
            throw ApiException.conflict("GRADE_NOT_ELIGIBLE", "学生年级不在课程适用范围内");
        }
        if (existing != null && "ENROLLED".equals(existing.getStatus())) {
            throw ApiException.conflict("ALREADY_ENROLLED", "该学生已经报名此开班");
        }
        if (hasScheduleConflict) {
            throw ApiException.conflict("STUDENT_SCHEDULE_CONFLICT", "该学生已有时间冲突的课程");
        }
        if (offering.getEnrolledCount() >= offering.getCapacity()) {
            throw ApiException.conflict("OFFERING_FULL", "该开班名额已满");
        }
    }

    public void validateCancellation(EnrollmentOffering offering, LocalDateTime now) {
        validateCancellation(offering, templateFirstSessionStart(offering), now);
    }

    public void validateCancellation(
            EnrollmentOffering offering,
            LocalDateTime firstSessionStart,
            LocalDateTime now) {
        if (!now.isBefore(firstSessionStart)) {
            throw ApiException.conflict(
                    "CANCELLATION_CLOSED",
                    "课程开始后不能由家长直接取消报名，请联系学校处理");
        }
    }

    public LocalDateTime templateFirstSessionStart(EnrollmentOffering offering) {
        LocalDate firstSessionDate = offering.getStartDate();
        DayOfWeek targetDay = DayOfWeek.of(offering.getWeekDay());
        while (firstSessionDate.getDayOfWeek() != targetDay) {
            firstSessionDate = firstSessionDate.plusDays(1);
        }
        return LocalDateTime.of(firstSessionDate, offering.getStartTime());
    }

    private boolean isClosedStatus(String status) {
        return "CLOSED".equals(status) || "ARCHIVED".equals(status);
    }
}
