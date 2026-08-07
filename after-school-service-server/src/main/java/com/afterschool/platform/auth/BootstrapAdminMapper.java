package com.afterschool.platform.auth;

import org.apache.ibatis.annotations.Param;

public interface BootstrapAdminMapper {

    int countUsername(@Param("username") String username);

    int insertRegulator(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName);
}
