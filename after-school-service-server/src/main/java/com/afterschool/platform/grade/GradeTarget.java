package com.afterschool.platform.grade;

public class GradeTarget {

    private long schoolId;
    private long offeringId;
    private long studentId;
    private long teacherId;
    private String enrollmentStatus;

    public long getSchoolId() { return schoolId; }
    public void setSchoolId(long schoolId) { this.schoolId = schoolId; }
    public long getOfferingId() { return offeringId; }
    public void setOfferingId(long offeringId) { this.offeringId = offeringId; }
    public long getStudentId() { return studentId; }
    public void setStudentId(long studentId) { this.studentId = studentId; }
    public long getTeacherId() { return teacherId; }
    public void setTeacherId(long teacherId) { this.teacherId = teacherId; }
    public String getEnrollmentStatus() { return enrollmentStatus; }
    public void setEnrollmentStatus(String enrollmentStatus) { this.enrollmentStatus = enrollmentStatus; }
}
