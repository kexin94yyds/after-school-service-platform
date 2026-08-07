package com.afterschool.platform.registration;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    private final EnrollmentMapper mapper;
    private final CurrentUser currentUser;
    private final EnrollmentRuleEngine rules;
    private final Clock clock;

    public EnrollmentService(
            EnrollmentMapper mapper,
            CurrentUser currentUser,
            EnrollmentRuleEngine rules,
            Clock clock) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.rules = rules;
        this.clock = clock;
    }

    public List<Map<String, Object>> guardianStudents() {
        return mapper.guardianStudents(requireGuardianId());
    }

    public List<Map<String, Object>> guardianOfferings(long studentId) {
        long guardianId = requireGuardianId();
        EnrollmentStudent student = mapper.findGuardianStudent(studentId, guardianId);
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (Map<String, Object> source : mapper.listGuardianOfferings(student.getSchoolId())) {
            long offeringId = ((Number) source.get("id")).longValue();
            EnrollmentOffering offering = mapper.findOffering(offeringId);
            EnrollmentState state = mapper.findEnrollmentState(offeringId, studentId);
            boolean conflict = mapper.countScheduleConflicts(studentId, offeringId) > 0;
            Map<String, Object> item = new LinkedHashMap<>(source);
            try {
                rules.validate(student, offering, state, conflict, LocalDateTime.now(clock));
                item.put("canEnroll", true);
                item.put("eligibilityCode", "ELIGIBLE");
                item.put("eligibilityMessage", "可报名");
            } catch (ApiException exception) {
                item.put("canEnroll", false);
                item.put("eligibilityCode", exception.code());
                item.put("eligibilityMessage", exception.getMessage());
            }
            item.put("enrollmentStatus", state == null ? null : state.getStatus());
            response.add(item);
        }
        return response;
    }

    public List<Map<String, Object>> guardianAttendance(long studentId) {
        EnrollmentStudent student = mapper.findGuardianStudent(studentId, requireGuardianId());
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        return mapper.listGuardianAttendance(studentId);
    }

    @Transactional
    public Map<String, Object> enroll(EnrollmentController.EnrollmentRequest request) {
        long guardianId = requireGuardianId();

        // Fixed lock order: student first, then offering. This serializes conflict checks
        // for the same child and capacity checks for the same offering.
        EnrollmentStudent student = mapper.lockGuardianStudent(request.studentId(), guardianId);
        if (student == null) {
            throw ApiException.notFound("学生不存在或未与当前家长绑定");
        }
        EnrollmentOffering offering = mapper.lockOffering(request.offeringId());
        if (offering == null) {
            throw ApiException.notFound("开班不存在");
        }

        EnrollmentState existing =
                mapper.findEnrollmentState(request.offeringId(), request.studentId());
        boolean conflict =
                mapper.countScheduleConflicts(request.studentId(), request.offeringId()) > 0;
        rules.validate(student, offering, existing, conflict, LocalDateTime.now(clock));

        if (mapper.incrementCapacity(request.offeringId()) != 1) {
            throw ApiException.conflict("OFFERING_FULL", "该开班名额已满");
        }
        if (existing == null) {
            mapper.insertEnrollment(
                    student.getSchoolId(),
                    request.offeringId(),
                    request.studentId(),
                    guardianId);
        } else {
            mapper.reactivateEnrollment(existing.getId(), guardianId);
        }
        return mapper.findEnrollmentView(request.offeringId(), request.studentId());
    }

    @Transactional
    public void cancel(long enrollmentId) {
        PlatformPrincipal principal = currentUser.principal();
        boolean guardianCancellation = "GUARDIAN".equals(principal.roleCode());
        EnrollmentRecord record;
        Long guardianId = null;
        if (guardianCancellation) {
            guardianId = requireGuardianId();
            record = mapper.findScopedEnrollment(enrollmentId, guardianId);
        } else if ("SCHOOL_ADMIN".equals(principal.roleCode())
                && principal.schoolId() != null) {
            record = mapper.findSchoolEnrollment(enrollmentId, principal.schoolId());
        } else {
            throw ApiException.forbidden("当前角色不能取消报名");
        }
        if (record == null) {
            throw ApiException.notFound("报名记录不存在");
        }

        if (guardianCancellation) {
            EnrollmentStudent student =
                    mapper.lockGuardianStudent(record.getStudentId(), guardianId);
            if (student == null) {
                throw ApiException.notFound("报名学生未与当前家长绑定");
            }
        } else if (mapper.lockSchoolStudent(
                        record.getStudentId(), principal.schoolId())
                == null) {
            throw ApiException.notFound("报名学生不存在或不在当前学校");
        }
        EnrollmentOffering offering = mapper.lockOffering(record.getOfferingId());
        if (offering == null) {
            throw ApiException.notFound("开班不存在");
        }
        EnrollmentRecord lockedRecord = guardianCancellation
                ? mapper.lockScopedEnrollment(enrollmentId, guardianId)
                : mapper.lockSchoolEnrollment(enrollmentId, principal.schoolId());
        if (lockedRecord == null
                || lockedRecord.getStudentId() != record.getStudentId()
                || lockedRecord.getOfferingId() != record.getOfferingId()) {
            throw ApiException.conflict(
                    "ENROLLMENT_CHANGED",
                    "报名归属或关联对象已变化，请刷新后重试");
        }
        if (guardianCancellation) {
            rules.validateCancellation(offering, LocalDateTime.now(clock));
        }
        if (!"ENROLLED".equals(lockedRecord.getStatus())) {
            throw ApiException.conflict("ALREADY_CANCELED", "该报名已经取消");
        }
        if (mapper.cancelEnrollment(
                                enrollmentId,
                                guardianCancellation ? guardianId : null,
                                guardianCancellation ? null : principal.schoolId(),
                                principal.id())
                        != 1
                || mapper.decrementCapacity(record.getOfferingId()) != 1) {
            throw ApiException.conflict("ENROLLMENT_CHANGED", "报名状态已变化，请刷新后重试");
        }
        mapper.withdrawActiveLeavesForEnrollment(
                offering.getSchoolId(),
                lockedRecord.getOfferingId(),
                lockedRecord.getStudentId(),
                principal.id());
    }

    public List<Map<String, Object>> enrollments(Long requestedSchoolId) {
        PlatformPrincipal principal = currentUser.principal();
        Long schoolId = currentUser.optionalSchoolScope(requestedSchoolId);
        Long teacherId = null;
        Long guardianId = null;
        if ("TEACHER".equals(principal.roleCode())) {
            if (principal.teacherId() == null) {
                throw ApiException.forbidden("当前教师账号缺少教师档案");
            }
            teacherId = principal.teacherId();
        }
        if ("GUARDIAN".equals(principal.roleCode())) {
            if (principal.guardianId() == null) {
                throw ApiException.forbidden("当前家长账号缺少家长档案");
            }
            guardianId = principal.guardianId();
        }
        return mapper.listEnrollments(schoolId, teacherId, guardianId);
    }

    private long requireGuardianId() {
        PlatformPrincipal principal = currentUser.principal();
        if (!"GUARDIAN".equals(principal.roleCode()) || principal.guardianId() == null) {
            throw ApiException.forbidden("当前账号不是有效家长账号");
        }
        return principal.guardianId();
    }
}
