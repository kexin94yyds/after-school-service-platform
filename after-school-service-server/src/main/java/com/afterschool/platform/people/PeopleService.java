package com.afterschool.platform.people;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeopleService {

    private final PeopleMapper mapper;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;

    public PeopleService(PeopleMapper mapper, CurrentUser currentUser, PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> teachers(Long schoolId) {
        return mapper.listTeachers(currentUser.optionalSchoolScope(schoolId));
    }

    @Transactional
    public Map<String, Object> createTeacher(PeopleController.TeacherRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireNewPassword(request.password());

        NewUser user = new NewUser();
        user.setSchoolId(schoolId);
        user.setRoleCode("TEACHER");
        user.setUsername(request.username().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.fullName().strip());
        user.setMobile(trimToNull(request.phone()));
        user.setEnabled("ACTIVE".equals(request.status()));
        mapper.insertUser(user);

        mapper.insertTeacher(
                schoolId,
                user.getId(),
                request.teacherNo().strip(),
                request.fullName().strip(),
                trimToNull(request.phone()),
                trimToNull(request.title()),
                request.status());
        return mapper.findTeacherByNo(schoolId, request.teacherNo().strip());
    }

    @Transactional
    public Map<String, Object> updateTeacher(long id, PeopleController.TeacherRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        Long userId = mapper.teacherUserId(id, schoolId);
        if (userId == null) {
            throw ApiException.notFound("教师不存在或不在当前学校");
        }
        mapper.updateTeacher(
                id,
                schoolId,
                request.teacherNo().strip(),
                request.fullName().strip(),
                trimToNull(request.phone()),
                trimToNull(request.title()),
                request.status());
        mapper.updateUserProfile(
                userId,
                request.username().strip(),
                request.fullName().strip(),
                trimToNull(request.phone()),
                "ACTIVE".equals(request.status()),
                encodedOrNull(request.password()));
        return mapper.findTeacherByNo(schoolId, request.teacherNo().strip());
    }

    public List<Map<String, Object>> students(Long schoolId) {
        return mapper.listStudents(currentUser.optionalSchoolScope(schoolId));
    }

    @Transactional
    public Map<String, Object> createStudent(PeopleController.StudentRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireClassInSchool(request.classId(), schoolId);
        mapper.insertStudent(
                schoolId,
                request.classId(),
                request.studentNo().strip(),
                request.fullName().strip(),
                request.gender(),
                request.dateOfBirth(),
                request.status());
        return mapper.findStudentByNo(schoolId, request.studentNo().strip());
    }

    @Transactional
    public Map<String, Object> updateStudent(long id, PeopleController.StudentRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireClassInSchool(request.classId(), schoolId);
        if (mapper.updateStudent(
                        id,
                        schoolId,
                        request.classId(),
                        request.studentNo().strip(),
                        request.fullName().strip(),
                        request.gender(),
                        request.dateOfBirth(),
                        request.status())
                == 0) {
            throw ApiException.notFound("学生不存在或不在当前学校");
        }
        return mapper.findStudentByNo(schoolId, request.studentNo().strip());
    }

    public List<Map<String, Object>> guardians(Long schoolId) {
        return normalizeGuardians(mapper.listGuardians(currentUser.optionalSchoolScope(schoolId)));
    }

    @Transactional
    public Map<String, Object> createGuardian(PeopleController.GuardianRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireNewPassword(request.password());
        requireStudentsInSchool(schoolId, request.studentIds());

        NewUser user = new NewUser();
        user.setSchoolId(schoolId);
        user.setRoleCode("GUARDIAN");
        user.setUsername(request.username().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.fullName().strip());
        user.setMobile(request.mobile().strip());
        user.setEnabled("ACTIVE".equals(request.status()));
        mapper.insertUser(user);

        NewGuardian guardian = new NewGuardian();
        guardian.setUserId(user.getId());
        guardian.setFullName(request.fullName().strip());
        guardian.setMobile(request.mobile().strip());
        guardian.setStatus(request.status());
        mapper.insertGuardian(guardian);
        replaceBindings(guardian.getId(), request);
        return normalizeGuardian(mapper.findGuardianByUsername(schoolId, request.username().strip()));
    }

    @Transactional
    public Map<String, Object> updateGuardian(long id, PeopleController.GuardianRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        requireStudentsInSchool(schoolId, request.studentIds());
        Long userId = mapper.guardianUserId(id, schoolId);
        if (userId == null) {
            throw ApiException.notFound("家长不存在或不在当前学校");
        }
        mapper.updateGuardian(
                id,
                schoolId,
                request.fullName().strip(),
                request.mobile().strip(),
                request.status());
        mapper.updateUserProfile(
                userId,
                request.username().strip(),
                request.fullName().strip(),
                request.mobile().strip(),
                "ACTIVE".equals(request.status()),
                encodedOrNull(request.password()));
        replaceBindings(id, request);
        mapper.deactivateGuardianBindingsExcept(id, request.studentIds());
        return normalizeGuardians(mapper.listGuardians(schoolId)).stream()
                .filter(item -> Long.valueOf(id).equals(((Number) item.get("id")).longValue()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("家长不存在"));
    }

    private void replaceBindings(long guardianId, PeopleController.GuardianRequest request) {
        for (int index = 0; index < request.studentIds().size(); index++) {
            mapper.insertGuardianBinding(
                    request.studentIds().get(index),
                    guardianId,
                    request.relationship().strip(),
                    index == 0 && request.primary());
        }
    }

    private void requireClassInSchool(long classId, long schoolId) {
        if (mapper.classExists(classId, schoolId) == 0) {
            throw ApiException.badRequest("INVALID_CLASS", "班级不存在或不属于当前学校");
        }
    }

    private void requireStudentsInSchool(long schoolId, List<Long> studentIds) {
        long distinctCount = studentIds.stream().distinct().count();
        if (distinctCount != studentIds.size()
                || mapper.countStudentsInSchool(schoolId, studentIds) != studentIds.size()) {
            throw ApiException.badRequest("INVALID_STUDENT_BINDING", "绑定学生必须存在且属于当前学校");
        }
    }

    private void requireNewPassword(String password) {
        if (password == null || password.length() < 12) {
            throw ApiException.badRequest("WEAK_PASSWORD", "初始密码至少需要 12 个字符");
        }
    }

    private String encodedOrNull(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        if (password.length() < 12) {
            throw ApiException.badRequest("WEAK_PASSWORD", "新密码至少需要 12 个字符");
        }
        return passwordEncoder.encode(password);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<Map<String, Object>> normalizeGuardians(List<Map<String, Object>> guardians) {
        return guardians.stream().map(this::normalizeGuardian).toList();
    }

    private Map<String, Object> normalizeGuardian(Map<String, Object> source) {
        Map<String, Object> guardian = new java.util.LinkedHashMap<>(source);
        Object rawStudentIds = guardian.get("studentIds");
        if (rawStudentIds == null || rawStudentIds.toString().isBlank()) {
            guardian.put("studentIds", List.of());
        } else {
            guardian.put(
                    "studentIds",
                    java.util.Arrays.stream(rawStudentIds.toString().split(","))
                            .map(String::strip)
                            .map(Long::valueOf)
                            .toList());
        }
        return guardian;
    }
}
