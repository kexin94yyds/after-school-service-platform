package com.afterschool.platform.people;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PeopleMapper {

    int insertUser(NewUser user);

    int updateUserProfile(
            @Param("userId") long userId,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("mobile") String mobile,
            @Param("enabled") boolean enabled,
            @Param("passwordHash") String passwordHash);

    List<Map<String, Object>> listTeachers(@Param("schoolId") Long schoolId);

    Map<String, Object> findTeacherByNo(
            @Param("schoolId") long schoolId, @Param("teacherNo") String teacherNo);

    int insertTeacher(
            @Param("schoolId") long schoolId,
            @Param("userId") long userId,
            @Param("teacherNo") String teacherNo,
            @Param("fullName") String fullName,
            @Param("phone") String phone,
            @Param("title") String title,
            @Param("status") String status);

    int updateTeacher(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("teacherNo") String teacherNo,
            @Param("fullName") String fullName,
            @Param("phone") String phone,
            @Param("title") String title,
            @Param("status") String status);

    Long teacherUserId(@Param("id") long id, @Param("schoolId") long schoolId);

    List<Map<String, Object>> listStudents(@Param("schoolId") Long schoolId);

    Map<String, Object> findStudentByNo(
            @Param("schoolId") long schoolId, @Param("studentNo") String studentNo);

    int classExists(@Param("classId") long classId, @Param("schoolId") long schoolId);

    int insertStudent(
            @Param("schoolId") long schoolId,
            @Param("classId") long classId,
            @Param("studentNo") String studentNo,
            @Param("fullName") String fullName,
            @Param("gender") String gender,
            @Param("dateOfBirth") LocalDate dateOfBirth,
            @Param("status") String status);

    int updateStudent(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("classId") long classId,
            @Param("studentNo") String studentNo,
            @Param("fullName") String fullName,
            @Param("gender") String gender,
            @Param("dateOfBirth") LocalDate dateOfBirth,
            @Param("status") String status);

    List<Map<String, Object>> listGuardians(@Param("schoolId") Long schoolId);

    Map<String, Object> findGuardianByUsername(
            @Param("schoolId") long schoolId, @Param("username") String username);

    int countStudentsInSchool(
            @Param("schoolId") long schoolId, @Param("studentIds") List<Long> studentIds);

    int insertGuardian(NewGuardian guardian);

    int insertGuardianBinding(
            @Param("studentId") long studentId,
            @Param("guardianId") long guardianId,
            @Param("relationship") String relationship,
            @Param("primary") boolean primary);

    Long guardianUserId(@Param("id") long id, @Param("schoolId") long schoolId);

    int updateGuardian(
            @Param("id") long id,
            @Param("schoolId") long schoolId,
            @Param("fullName") String fullName,
            @Param("mobile") String mobile,
            @Param("status") String status);

    int deactivateGuardianBindingsExcept(
            @Param("guardianId") long guardianId,
            @Param("studentIds") List<Long> studentIds);
}
