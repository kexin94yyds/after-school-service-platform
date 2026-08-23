package com.afterschool.platform.leavecorrection;

public class LeaveRequestRecord extends SessionWorkflowContext {

    private long id;
    private long studentId;
    private Long guardianId;
    private long submittedBy;
    private String status;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getStudentId() {
        return studentId;
    }

    public void setStudentId(long studentId) {
        this.studentId = studentId;
    }

    public Long getGuardianId() {
        return guardianId;
    }

    public void setGuardianId(Long guardianId) {
        this.guardianId = guardianId;
    }

    public long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
