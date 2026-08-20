package com.afterschool.platform.organization;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OrganizationMapper {

    List<Map<String, Object>> listSchools(@Param("schoolId") Long schoolId);

    Map<String, Object> findSchoolByCode(@Param("schoolCode") String schoolCode);

    int insertSchool(
            @Param("schoolCode") String schoolCode,
            @Param("schoolName") String schoolName,
            @Param("districtCode") String districtCode,
            @Param("address") String address,
            @Param("contactPhone") String contactPhone,
            @Param("status") String status);

    int updateSchool(
            @Param("id") long id,
            @Param("schoolName") String schoolName,
            @Param("districtCode") String districtCode,
            @Param("address") String address,
            @Param("contactPhone") String contactPhone,
            @Param("status") String status);

    int schoolExists(@Param("schoolId") long schoolId);

    int countSchools(@Param("schoolIds") List<Long> schoolIds);

    List<Map<String, Object>> listRegulators();

    Map<String, Object> findRegulator(@Param("id") long id);

    Map<String, Object> findRegulatorByUsername(@Param("username") String username);

    int insertRegulator(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName,
            @Param("mobile") String mobile,
            @Param("enabled") boolean enabled);

    int updateRegulator(
            @Param("id") long id,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("mobile") String mobile,
            @Param("enabled") boolean enabled,
            @Param("passwordHash") String passwordHash);

    int deleteRegulatorScopes(@Param("regulatorUserId") long regulatorUserId);

    int insertRegulatorScope(
            @Param("regulatorUserId") long regulatorUserId,
            @Param("schoolId") long schoolId,
            @Param("assignedBy") long assignedBy);

    List<Map<String, Object>> listSchoolAdmins(@Param("schoolId") long schoolId);

    Map<String, Object> findSchoolAdminByUsername(
            @Param("schoolId") long schoolId,
            @Param("username") String username);

    Map<String, Object> findSchoolAdmin(
            @Param("schoolId") long schoolId,
            @Param("id") long id);

    int insertSchoolAdmin(
            @Param("schoolId") long schoolId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName,
            @Param("mobile") String mobile,
            @Param("enabled") boolean enabled);

    int updateSchoolAdmin(
            @Param("schoolId") long schoolId,
            @Param("id") long id,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("mobile") String mobile,
            @Param("enabled") boolean enabled,
            @Param("passwordHash") String passwordHash);

    List<Map<String, Object>> listClasses(@Param("schoolId") Long schoolId);

    Map<String, Object> findClassByNaturalKey(
            @Param("schoolId") long schoolId,
            @Param("schoolYear") String schoolYear,
            @Param("grade") int grade,
            @Param("className") String className);

    int insertClass(
            @Param("schoolId") long schoolId,
            @Param("className") String className,
            @Param("grade") int grade,
            @Param("schoolYear") String schoolYear,
            @Param("status") String status);

    int updateClass(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("className") String className,
            @Param("grade") int grade,
            @Param("schoolYear") String schoolYear,
            @Param("status") String status);
}
