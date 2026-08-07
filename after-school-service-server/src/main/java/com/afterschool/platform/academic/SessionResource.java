package com.afterschool.platform.academic;

import java.time.LocalDate;
import java.time.LocalTime;

public class SessionResource {

    private long id;
    private long schoolId;
    private long offeringId;
    private long teacherId;
    private Long termId;
    private LocalDate termStartDate;
    private LocalDate termEndDate;
    private LocalDate offeringStartDate;
    private LocalDate offeringEndDate;
    private int offeringCapacity;
    private LocalDate sessionDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Long roomId;
    private String classroom;
    private String status;
    private String offeringStatus;

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

    public long getOfferingId() {
        return offeringId;
    }

    public void setOfferingId(long offeringId) {
        this.offeringId = offeringId;
    }

    public long getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(long teacherId) {
        this.teacherId = teacherId;
    }

    public Long getTermId() {
        return termId;
    }

    public void setTermId(Long termId) {
        this.termId = termId;
    }

    public LocalDate getTermStartDate() {
        return termStartDate;
    }

    public void setTermStartDate(LocalDate termStartDate) {
        this.termStartDate = termStartDate;
    }

    public LocalDate getTermEndDate() {
        return termEndDate;
    }

    public void setTermEndDate(LocalDate termEndDate) {
        this.termEndDate = termEndDate;
    }

    public LocalDate getOfferingStartDate() {
        return offeringStartDate;
    }

    public void setOfferingStartDate(LocalDate offeringStartDate) {
        this.offeringStartDate = offeringStartDate;
    }

    public LocalDate getOfferingEndDate() {
        return offeringEndDate;
    }

    public void setOfferingEndDate(LocalDate offeringEndDate) {
        this.offeringEndDate = offeringEndDate;
    }

    public int getOfferingCapacity() {
        return offeringCapacity;
    }

    public void setOfferingCapacity(int offeringCapacity) {
        this.offeringCapacity = offeringCapacity;
    }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public void setSessionDate(LocalDate sessionDate) {
        this.sessionDate = sessionDate;
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

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getClassroom() {
        return classroom;
    }

    public void setClassroom(String classroom) {
        this.classroom = classroom;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOfferingStatus() {
        return offeringStatus;
    }

    public void setOfferingStatus(String offeringStatus) {
        this.offeringStatus = offeringStatus;
    }
}
