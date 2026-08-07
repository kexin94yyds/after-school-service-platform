package com.afterschool.platform.auth;

import org.apache.ibatis.annotations.Param;

public interface UserAccountMapper {

    UserAccount findByUsername(@Param("username") String username);

    UserAccount findActiveById(@Param("id") long id);

    void updateLastLogin(@Param("id") long id);

    int updatePassword(
            @Param("id") long id,
            @Param("expectedPasswordHash") String expectedPasswordHash,
            @Param("passwordHash") String passwordHash);
}
