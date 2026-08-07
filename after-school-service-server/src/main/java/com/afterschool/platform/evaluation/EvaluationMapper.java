package com.afterschool.platform.evaluation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface EvaluationMapper {

    Long lockStudent(
            @Param("studentId") long studentId,
            @Param("schoolId") long schoolId);

    Long lockOffering(
            @Param("offeringId") long offeringId,
            @Param("schoolId") long schoolId);

    EvaluationEligibility lockEligibility(
            @Param("studentId") long studentId,
            @Param("offeringId") long offeringId,
            @Param("guardianId") long guardianId,
            @Param("schoolId") long schoolId);

    int countEvaluation(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    int insertEvaluation(
            @Param("schoolId") long schoolId,
            @Param("offeringId") long offeringId,
            @Param("enrollmentId") long enrollmentId,
            @Param("studentId") long studentId,
            @Param("guardianId") long guardianId,
            @Param("rating") int rating,
            @Param("comment") String comment);

    Map<String, Object> findEvaluation(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    List<Map<String, Object>> listGuardianEvaluations(
            @Param("guardianId") long guardianId,
            @Param("schoolId") long schoolId);

    List<Map<String, Object>> listEvaluations(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("category") String category,
            @Param("minRating") Integer minRating,
            @Param("maxRating") Integer maxRating,
            @Param("submittedFrom") LocalDateTime submittedFrom,
            @Param("submittedTo") LocalDateTime submittedTo);

    Map<String, Object> evaluationSummary(
            @Param("schoolId") Long schoolId,
            @Param("termId") Long termId,
            @Param("category") String category,
            @Param("submittedFrom") LocalDateTime submittedFrom,
            @Param("submittedTo") LocalDateTime submittedTo);
}
