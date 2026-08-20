package com.afterschool.platform.registration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterschool.platform.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnrollmentRuleEngineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    private EnrollmentRuleEngine rules;
    private EnrollmentStudent student;
    private EnrollmentOffering offering;

    @BeforeEach
    void setUp() {
        rules = new EnrollmentRuleEngine();
        student = student(1, 1, 3, "ACTIVE");
        offering = offering(10, 1, 1, 6, "PUBLISHED", 20, 8);
    }

    @Test
    void acceptsEligibleEnrollment() {
        assertThatCode(() -> rules.validate(student, offering, null, false, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOutsideEnrollmentWindow() {
        offering.setEnrollmentEnd(NOW.minusSeconds(1));
        assertCode("OUTSIDE_ENROLLMENT_WINDOW", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsIneligibleGrade() {
        student.setGrade(7);
        assertCode("GRADE_NOT_ELIGIBLE", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsDuplicateEnrollment() {
        EnrollmentState state = new EnrollmentState();
        state.setId(99);
        state.setStatus("ENROLLED");
        assertCode("ALREADY_ENROLLED", () ->
                rules.validate(student, offering, state, false, NOW));
    }

    @Test
    void rejectsScheduleConflict() {
        assertCode("STUDENT_SCHEDULE_CONFLICT", () ->
                rules.validate(student, offering, null, true, NOW));
    }

    @Test
    void rejectsFullOffering() {
        offering.setEnrolledCount(offering.getCapacity());
        assertCode("OFFERING_FULL", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsCrossSchoolEnrollment() {
        offering.setSchoolId(2);
        assertCode("FORBIDDEN", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsNonPublishedOffering() {
        offering.setStatus("DRAFT");
        assertCode("OFFERING_NOT_OPEN", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsInactiveCourse() {
        offering.setCourseStatus("INACTIVE");
        assertCode("COURSE_INACTIVE", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsEnrollmentWhenLinkedTermIsClosed() {
        offering.setTermStatus("CLOSED");
        assertCode("TERM_CLOSED", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsEnrollmentWhenLinkedServicePlanIsClosed() {
        offering.setTermStatus("ACTIVE");
        offering.setPlanStatus("CLOSED");
        assertCode("SERVICE_PLAN_CLOSED", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void allowsEnrollmentWhenLinkedServicePlanIsFiled() {
        offering.setTermStatus("ACTIVE");
        offering.setPlanStatus("FILED");

        assertThatCode(() -> rules.validate(student, offering, null, false, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPublishedOfferingWithoutFiledPlanReferences() {
        offering.setPlanStatus(null);

        assertCode("OFFERING_NOT_FILED", () ->
                rules.validate(student, offering, null, false, NOW));
    }

    @Test
    void rejectsEnrollmentAfterFirstSessionStarts() {
        offering.setEnrollmentEnd(LocalDateTime.of(2026, 12, 31, 23, 59));
        assertCode("ENROLLMENT_CLOSED_AFTER_START", () ->
                rules.validate(
                        student,
                        offering,
                        null,
                        false,
                        LocalDateTime.of(2026, 9, 1, 16, 30)));
    }

    @Test
    void rejectsEnrollmentAfterAnActualFirstSessionWasMovedEarlierThanTheTemplate() {
        offering.setStartDate(LocalDate.of(2026, 9, 15));
        offering.setEnrollmentEnd(LocalDateTime.of(2026, 12, 31, 23, 59));

        assertCode("ENROLLMENT_CLOSED_AFTER_START", () ->
                rules.validate(
                        student,
                        offering,
                        null,
                        false,
                        LocalDateTime.of(2026, 9, 10, 15, 0),
                        LocalDateTime.of(2026, 9, 10, 16, 0)));
    }

    @Test
    void rejectsCancellationAfterFirstSessionStarts() {
        assertCode("CANCELLATION_CLOSED", () ->
                rules.validateCancellation(
                        offering, LocalDateTime.of(2026, 9, 1, 16, 30)));
    }

    @Test
    void allowsCancellationBeforeFirstSession() {
        assertThatCode(() ->
                        rules.validateCancellation(
                                offering, LocalDateTime.of(2026, 9, 1, 16, 29)))
                .doesNotThrowAnyException();
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(code);
    }

    private EnrollmentStudent student(long id, long schoolId, int grade, String status) {
        EnrollmentStudent value = new EnrollmentStudent();
        value.setId(id);
        value.setSchoolId(schoolId);
        value.setGrade(grade);
        value.setStatus(status);
        return value;
    }

    private EnrollmentOffering offering(
            long id,
            long schoolId,
            int minGrade,
            int maxGrade,
            String status,
            int capacity,
            int enrolledCount) {
        EnrollmentOffering value = new EnrollmentOffering();
        value.setId(id);
        value.setSchoolId(schoolId);
        value.setTargetGradeMin(minGrade);
        value.setTargetGradeMax(maxGrade);
        value.setWeekDay(2);
        value.setStartTime(LocalTime.of(16, 30));
        value.setEndTime(LocalTime.of(17, 30));
        value.setStartDate(LocalDate.of(2026, 9, 1));
        value.setEndDate(LocalDate.of(2027, 1, 31));
        value.setEnrollmentStart(NOW.minusDays(1));
        value.setEnrollmentEnd(NOW.plusDays(1));
        value.setCapacity(capacity);
        value.setEnrolledCount(enrolledCount);
        value.setStatus(status);
        value.setCourseStatus("ACTIVE");
        value.setTermStatus("ACTIVE");
        value.setPlanStatus("FILED");
        return value;
    }
}
