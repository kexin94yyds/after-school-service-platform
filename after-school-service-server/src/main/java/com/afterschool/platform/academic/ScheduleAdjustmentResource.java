package com.afterschool.platform.academic;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Locked domain snapshot used to restore one still-effective schedule
 * adjustment without overwriting a later adjustment for the same lesson.
 */
public class ScheduleAdjustmentResource {

    private long id;
    private long schoolId;
    private long sessionId;
    private LocalDate originalSessionDate;
    private LocalTime originalStartTime;
    private LocalTime originalEndTime;
    private Long originalRoomId;
    private String originalClassroom;
    private LocalDate adjustedSessionDate;
    private LocalTime adjustedStartTime;
    private LocalTime adjustedEndTime;
    private Long adjustedRoomId;
    private String adjustedClassroom;
    private String status;

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

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDate getOriginalSessionDate() {
        return originalSessionDate;
    }

    public void setOriginalSessionDate(LocalDate originalSessionDate) {
        this.originalSessionDate = originalSessionDate;
    }

    public LocalTime getOriginalStartTime() {
        return originalStartTime;
    }

    public void setOriginalStartTime(LocalTime originalStartTime) {
        this.originalStartTime = originalStartTime;
    }

    public LocalTime getOriginalEndTime() {
        return originalEndTime;
    }

    public void setOriginalEndTime(LocalTime originalEndTime) {
        this.originalEndTime = originalEndTime;
    }

    public Long getOriginalRoomId() {
        return originalRoomId;
    }

    public void setOriginalRoomId(Long originalRoomId) {
        this.originalRoomId = originalRoomId;
    }

    public String getOriginalClassroom() {
        return originalClassroom;
    }

    public void setOriginalClassroom(String originalClassroom) {
        this.originalClassroom = originalClassroom;
    }

    public LocalDate getAdjustedSessionDate() {
        return adjustedSessionDate;
    }

    public void setAdjustedSessionDate(LocalDate adjustedSessionDate) {
        this.adjustedSessionDate = adjustedSessionDate;
    }

    public LocalTime getAdjustedStartTime() {
        return adjustedStartTime;
    }

    public void setAdjustedStartTime(LocalTime adjustedStartTime) {
        this.adjustedStartTime = adjustedStartTime;
    }

    public LocalTime getAdjustedEndTime() {
        return adjustedEndTime;
    }

    public void setAdjustedEndTime(LocalTime adjustedEndTime) {
        this.adjustedEndTime = adjustedEndTime;
    }

    public Long getAdjustedRoomId() {
        return adjustedRoomId;
    }

    public void setAdjustedRoomId(Long adjustedRoomId) {
        this.adjustedRoomId = adjustedRoomId;
    }

    public String getAdjustedClassroom() {
        return adjustedClassroom;
    }

    public void setAdjustedClassroom(String adjustedClassroom) {
        this.adjustedClassroom = adjustedClassroom;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
