package com.afterschool.platform.auth;

import org.apache.ibatis.annotations.Param;

public interface BootstrapAdminMapper {

    int countSchoolAdmins();

    int countSchools();

    int countUsername(@Param("username") String username);

    int insertSchool(
            @Param("schoolCode") String schoolCode,
            @Param("schoolName") String schoolName,
            @Param("districtCode") String districtCode);

    Long findSchoolIdByCode(@Param("schoolCode") String schoolCode);

    int insertSchoolAdmin(
            @Param("schoolId") long schoolId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName);
}
