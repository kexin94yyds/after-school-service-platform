package com.afterschool.platform.organization;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.audit.AuditTargetContext;
import com.afterschool.platform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationMapper mapper;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;

    public OrganizationService(
            OrganizationMapper mapper,
            CurrentUser currentUser,
            PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> schools() {
        return mapper.listSchools(currentUser.optionalSchoolScope(null));
    }

    public List<Map<String, Object>> regulators() {
        return mapper.listRegulators();
    }

    @Transactional
    public Map<String, Object> createRegulator(
            OrganizationController.RegulatorRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw ApiException.badRequest(
                    "PASSWORD_REQUIRED", "创建监管账号时必须设置初始密码");
        }
        requireSchools(request.schoolIds());
        String username = request.username().strip();
        mapper.insertRegulator(
                username,
                passwordEncoder.encode(request.password()),
                request.displayName().strip(),
                trimToNull(request.mobile()),
                request.enabled());
        Map<String, Object> created = mapper.findRegulatorByUsername(username);
        if (created == null || !(created.get("id") instanceof Number id)) {
            throw ApiException.conflict(
                    "REGULATOR_NOT_CREATED", "监管账号未能创建");
        }
        replaceRegulatorScopes(id.longValue(), request.schoolIds());
        return mapper.findRegulator(id.longValue());
    }

    @Transactional
    public Map<String, Object> updateRegulator(
            long id,
            OrganizationController.RegulatorRequest request) {
        if (id == currentUser.principal().id() && !request.enabled()) {
            throw ApiException.conflict(
                    "SELF_DISABLE_NOT_ALLOWED", "不能停用当前登录的监管账号");
        }
        requireSchools(request.schoolIds());
        String passwordHash = request.password() == null || request.password().isBlank()
                ? null
                : passwordEncoder.encode(request.password());
        if (mapper.updateRegulator(
                        id,
                        request.username().strip(),
                        request.displayName().strip(),
                        trimToNull(request.mobile()),
                        request.enabled(),
                        passwordHash)
                != 1) {
            throw ApiException.notFound("监管账号不存在");
        }
        replaceRegulatorScopes(id, request.schoolIds());
        return mapper.findRegulator(id);
    }

    @Transactional
    public Map<String, Object> createSchool(OrganizationController.SchoolRequest request) {
        mapper.insertSchool(
                request.schoolCode().strip(),
                request.schoolName().strip(),
                request.districtCode().strip(),
                trimToNull(request.address()),
                trimToNull(request.contactPhone()),
                request.status());
        Map<String, Object> created =
                mapper.findSchoolByCode(request.schoolCode().strip());
        if (created != null && created.get("id") instanceof Number id) {
            AuditTargetContext.setTargetSchoolId(id.longValue());
        }
        return created;
    }

    @Transactional
    public Map<String, Object> updateSchool(long id, OrganizationController.SchoolRequest request) {
        AuditTargetContext.setTargetSchoolId(id);
        if (mapper.updateSchool(
                        id,
                        request.schoolName().strip(),
                        request.districtCode().strip(),
                        trimToNull(request.address()),
                        trimToNull(request.contactPhone()),
                        request.status())
                == 0) {
            throw ApiException.notFound("学校不存在");
        }
        return mapper.listSchools(id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("学校不存在"));
    }

    public List<Map<String, Object>> schoolAdmins(long schoolId) {
        requireSchool(schoolId);
        return mapper.listSchoolAdmins(schoolId);
    }

    @Transactional
    public Map<String, Object> createSchoolAdmin(
            long schoolId,
            OrganizationController.SchoolAdminRequest request) {
        requireSchool(schoolId);
        if (request.password() == null || request.password().isBlank()) {
            throw ApiException.badRequest("PASSWORD_REQUIRED", "创建管理员时必须设置初始密码");
        }
        String username = request.username().strip();
        mapper.insertSchoolAdmin(
                schoolId,
                username,
                passwordEncoder.encode(request.password()),
                request.displayName().strip(),
                trimToNull(request.mobile()),
                request.enabled());
        return mapper.findSchoolAdminByUsername(schoolId, username);
    }

    @Transactional
    public Map<String, Object> updateSchoolAdmin(
            long schoolId,
            long id,
            OrganizationController.SchoolAdminRequest request) {
        requireSchool(schoolId);
        String passwordHash = request.password() == null || request.password().isBlank()
                ? null
                : passwordEncoder.encode(request.password());
        if (mapper.updateSchoolAdmin(
                        schoolId,
                        id,
                        request.username().strip(),
                        request.displayName().strip(),
                        trimToNull(request.mobile()),
                        request.enabled(),
                        passwordHash)
                != 1) {
            throw ApiException.notFound("学校管理员不存在或不属于该学校");
        }
        return mapper.findSchoolAdmin(schoolId, id);
    }

    public List<Map<String, Object>> classes(Long requestedSchoolId) {
        return mapper.listClasses(currentUser.optionalSchoolScope(requestedSchoolId));
    }

    @Transactional
    public Map<String, Object> createClass(OrganizationController.ClassRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        mapper.insertClass(
                schoolId,
                request.className().strip(),
                request.grade(),
                request.schoolYear().strip(),
                request.status());
        return mapper.findClassByNaturalKey(
                schoolId,
                request.schoolYear().strip(),
                request.grade(),
                request.className().strip());
    }

    @Transactional
    public Map<String, Object> updateClass(long id, OrganizationController.ClassRequest request) {
        long schoolId = currentUser.schoolScope(request.schoolId());
        if (mapper.updateClass(
                        id,
                        schoolId,
                        request.className().strip(),
                        request.grade(),
                        request.schoolYear().strip(),
                        request.status())
                == 0) {
            throw ApiException.notFound("班级不存在或不在当前学校");
        }
        return mapper.findClassByNaturalKey(
                schoolId,
                request.schoolYear().strip(),
                request.grade(),
                request.className().strip());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private void requireSchool(long schoolId) {
        AuditTargetContext.setTargetSchoolId(schoolId);
        if (mapper.schoolExists(schoolId) != 1) {
            throw ApiException.notFound("学校不存在");
        }
    }

    private void requireSchools(List<Long> schoolIds) {
        long distinct = schoolIds.stream().distinct().count();
        if (distinct != schoolIds.size()
                || mapper.countSchools(schoolIds) != schoolIds.size()) {
            throw ApiException.badRequest(
                    "INVALID_REGULATOR_SCOPE",
                    "监管范围中的学校必须存在且不能重复");
        }
    }

    private void replaceRegulatorScopes(
            long regulatorUserId,
            List<Long> schoolIds) {
        mapper.deleteRegulatorScopes(regulatorUserId);
        long actorId = currentUser.principal().id();
        for (long schoolId : schoolIds) {
            mapper.insertRegulatorScope(regulatorUserId, schoolId, actorId);
        }
    }
}
