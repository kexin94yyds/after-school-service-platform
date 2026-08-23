package com.afterschool.platform.grade;

import java.math.BigDecimal;

public class GradeRecord {

    private long id;
    private long schoolId;
    private BigDecimal score;
    private String learningEvaluation;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getSchoolId() { return schoolId; }
    public void setSchoolId(long schoolId) { this.schoolId = schoolId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getLearningEvaluation() { return learningEvaluation; }
    public void setLearningEvaluation(String learningEvaluation) { this.learningEvaluation = learningEvaluation; }
}
