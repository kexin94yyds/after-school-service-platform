package com.afterschool.platform.leavecorrection;

import java.time.LocalDateTime;

public class AttendanceCorrectionRecord extends SessionWorkflowContext {

    private long id;
    private long attendanceId;
    private long studentId;
    private long requestedBy;
    private String requestedStatus;
    private String requestedRemark;
    private String reason;
    private String status;
    private String attendanceStatus;
    private String attendanceRemark;
    private long attendanceRecordedBy;
    private LocalDateTime attendanceRecordedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAttendanceId() {
        return attendanceId;
    }

    public void setAttendanceId(long attendanceId) {
        this.attendanceId = attendanceId;
    }

    public long getStudentId() {
        return studentId;
    }

    public void setStudentId(long studentId) {
        this.studentId = studentId;
    }

    public long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRequestedStatus() {
        return requestedStatus;
    }

    public void setRequestedStatus(String requestedStatus) {
        this.requestedStatus = requestedStatus;
    }

    public String getRequestedRemark() {
        return requestedRemark;
    }

    public void setRequestedRemark(String requestedRemark) {
        this.requestedRemark = requestedRemark;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAttendanceStatus() {
        return attendanceStatus;
    }

    public void setAttendanceStatus(String attendanceStatus) {
        this.attendanceStatus = attendanceStatus;
    }

    public String getAttendanceRemark() {
        return attendanceRemark;
    }

    public void setAttendanceRemark(String attendanceRemark) {
        this.attendanceRemark = attendanceRemark;
    }

    public long getAttendanceRecordedBy() {
        return attendanceRecordedBy;
    }

    public void setAttendanceRecordedBy(long attendanceRecordedBy) {
        this.attendanceRecordedBy = attendanceRecordedBy;
    }

    public LocalDateTime getAttendanceRecordedAt() {
        return attendanceRecordedAt;
    }

    public void setAttendanceRecordedAt(LocalDateTime attendanceRecordedAt) {
        this.attendanceRecordedAt = attendanceRecordedAt;
    }
}
