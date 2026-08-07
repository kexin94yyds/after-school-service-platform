package com.afterschool.platform.registration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class EnrollmentOffering {

    private long id;
    private long schoolId;
    private int targetGradeMin;
    private int targetGradeMax;
    private int weekDay;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime enrollmentStart;
    private LocalDateTime enrollmentEnd;
    private int capacity;
    private int enrolledCount;
    private String status;
    private String courseStatus;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(long schoolId) {
        this.schoolId = schoolId;
    }

    public int getTargetGradeMin() {
        return targetGradeMin;
    }

    public void setTargetGradeMin(int targetGradeMin) {
        this.targetGradeMin = targetGradeMin;
    }

    public int getTargetGradeMax() {
        return targetGradeMax;
    }

    public void setTargetGradeMax(int targetGradeMax) {
        this.targetGradeMax = targetGradeMax;
    }

    public int getWeekDay() {
        return weekDay;
    }

    public void setWeekDay(int weekDay) {
        this.weekDay = weekDay;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public LocalDateTime getEnrollmentStart() {
        return enrollmentStart;
    }

    public void setEnrollmentStart(LocalDateTime enrollmentStart) {
        this.enrollmentStart = enrollmentStart;
    }

    public LocalDateTime getEnrollmentEnd() {
        return enrollmentEnd;
    }

    public void setEnrollmentEnd(LocalDateTime enrollmentEnd) {
        this.enrollmentEnd = enrollmentEnd;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    public void setEnrolledCount(int enrolledCount) {
        this.enrolledCount = enrolledCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCourseStatus() {
        return courseStatus;
    }

    public void setCourseStatus(String courseStatus) {
        this.courseStatus = courseStatus;
    }
}
