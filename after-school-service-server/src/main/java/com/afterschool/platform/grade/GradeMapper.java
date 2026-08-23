package com.afterschool.platform.grade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface GradeMapper {

    GradeTarget lockTarget(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    GradeRecord lockGrade(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    int insertGrade(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("teacherId") long teacherId,
            @Param("score") BigDecimal score,
            @Param("learningEvaluation") String learningEvaluation,
            @Param("userId") long userId);

    int insertRevision(
            @Param("record") GradeRecord record,
            @Param("newScore") BigDecimal newScore,
            @Param("newLearningEvaluation") String newLearningEvaluation,
            @Param("changedBy") long changedBy);

    int updateGrade(
            @Param("id") long id,
            @Param("score") BigDecimal score,
            @Param("learningEvaluation") String learningEvaluation,
            @Param("updatedBy") long updatedBy);

    Map<String, Object> findGrade(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    List<Map<String, Object>> listRoster(
            @Param("offeringId") long offeringId,
            @Param("teacherId") long teacherId);

    List<Map<String, Object>> listGrades(
            @Param("schoolId") Long schoolId,
            @Param("teacherId") Long teacherId,
            @Param("guardianId") Long guardianId,
            @Param("studentId") Long studentId,
            @Param("offeringId") Long offeringId);

    List<Map<String, Object>> gradeSummary(
            @Param("schoolId") long schoolId,
            @Param("teacherId") Long teacherId);

    List<Map<String, Object>> listRevisions(
            @Param("gradeId") long gradeId,
            @Param("schoolId") long schoolId,
            @Param("teacherId") Long teacherId);
}
