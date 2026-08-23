package com.afterschool.platform.grade;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.common.ApiException;
import com.afterschool.platform.common.excel.SimpleXlsx;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeService {

    private final GradeMapper mapper;
    private final CurrentUser currentUser;

    public GradeService(GradeMapper mapper, CurrentUser currentUser) {
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    @Transactional
    public Map<String, Object> save(GradeController.GradeRequest request) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"TEACHER".equals(principal.roleCode()) || principal.teacherId() == null) {
            throw ApiException.forbidden("只有任课教师可以录入成绩");
        }
        GradeTarget target = mapper.lockTarget(request.offeringId(), request.studentId());
        if (target == null
                || target.getTeacherId() != principal.teacherId()
                || target.getSchoolId() != principal.schoolId()
                || !"ENROLLED".equals(target.getEnrollmentStatus())) {
            throw ApiException.forbidden("只能为本人课程的有效报名学生录入成绩");
        }
        BigDecimal score = request.score().stripTrailingZeros();
        String evaluation = normalize(request.learningEvaluation());
        GradeRecord existing = mapper.lockGrade(request.offeringId(), request.studentId());
        if (existing == null) {
            if (mapper.insertGrade(
                            target.getSchoolId(),
                            request.offeringId(),
                            request.studentId(),
                            target.getTeacherId(),
                            score,
                            evaluation,
                            principal.id())
                    != 1) {
                throw ApiException.conflict("GRADE_NOT_SAVED", "成绩未能保存");
            }
        } else if (existing.getScore().compareTo(score) != 0
                || !Objects.equals(existing.getLearningEvaluation(), evaluation)) {
            if (mapper.insertRevision(existing, score, evaluation, principal.id()) != 1
                    || mapper.updateGrade(existing.getId(), score, evaluation, principal.id()) != 1) {
                throw ApiException.conflict("GRADE_CHANGED", "成绩已变化，请刷新后重试");
            }
        }
        return mapper.findGrade(request.offeringId(), request.studentId());
    }

    public List<Map<String, Object>> roster(long offeringId) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"TEACHER".equals(principal.roleCode()) || principal.teacherId() == null) {
            throw ApiException.forbidden("只有任课教师可以查看成绩名单");
        }
        return mapper.listRoster(offeringId, principal.teacherId());
    }

    public List<Map<String, Object>> list(Long requestedOfferingId) {
        PlatformPrincipal principal = currentUser.principal();
        Long schoolId = principal.schoolId();
        Long teacherId = null;
        Long guardianId = null;
        Long studentId = null;
        switch (principal.roleCode()) {
            case "SCHOOL_ADMIN" -> { }
            case "TEACHER" -> teacherId = requireId(principal.teacherId(), "教师");
            case "GUARDIAN" -> guardianId = requireId(principal.guardianId(), "家长");
            case "STUDENT" -> studentId = requireId(principal.studentId(), "学生");
            default -> throw ApiException.forbidden("当前角色不能查看成绩");
        }
        return mapper.listGrades(schoolId, teacherId, guardianId, studentId, requestedOfferingId);
    }

    public List<Map<String, Object>> summary() {
        PlatformPrincipal principal = currentUser.principal();
        if (principal.schoolId() == null) {
            throw ApiException.forbidden("当前账号缺少学校数据权限");
        }
        Long teacherId = "TEACHER".equals(principal.roleCode())
                ? requireId(principal.teacherId(), "教师")
                : null;
        if (teacherId == null && !"SCHOOL_ADMIN".equals(principal.roleCode())) {
            throw ApiException.forbidden("当前角色不能查看成绩汇总");
        }
        return mapper.gradeSummary(principal.schoolId(), teacherId);
    }

    public byte[] gradesXlsx() {
        PlatformPrincipal principal = currentUser.principal();
        if (!"SCHOOL_ADMIN".equals(principal.roleCode())) {
            throw ApiException.forbidden("只有教务管理员可以导出成绩");
        }
        List<Map<String, Object>> rows = list(null);
        List<String> headers = List.of(
                "班级", "学号", "学生姓名", "课程", "教师", "成绩", "学习评价", "更新时间");
        List<? extends List<?>> data = rows.stream().map(row -> List.of(
                value(row, "className"), value(row, "studentNo"), value(row, "studentName"),
                value(row, "courseName"), value(row, "teacherName"), value(row, "score"),
                value(row, "learningEvaluation"), value(row, "updatedAt"))).toList();
        return SimpleXlsx.write("学生成绩", headers, data);
    }

    public List<Map<String, Object>> revisions(long gradeId) {
        PlatformPrincipal principal = currentUser.principal();
        if (principal.schoolId() == null
                || (!"TEACHER".equals(principal.roleCode())
                    && !"SCHOOL_ADMIN".equals(principal.roleCode()))) {
            throw ApiException.forbidden("当前角色不能查看成绩修改历史");
        }
        Long teacherId = "TEACHER".equals(principal.roleCode())
                ? requireId(principal.teacherId(), "教师")
                : null;
        return mapper.listRevisions(gradeId, principal.schoolId(), teacherId);
    }

    private long requireId(Long value, String identity) {
        if (value == null) {
            throw ApiException.forbidden("当前账号缺少" + identity + "档案");
        }
        return value;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value;
    }
}
