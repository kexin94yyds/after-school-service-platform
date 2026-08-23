package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterschool.platform.leavecorrection.LeaveCorrectionController;
import com.afterschool.platform.teaching.TeachingController;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class FourRoleAuthorizationContractTest {

    @Test
    void loginContractAcceptsExactlyFourRolePortals() throws Exception {
        RecordComponent expectedRole = Arrays.stream(
                        AuthController.LoginRequest.class.getRecordComponents())
                .filter(component -> component.getName().equals("expectedRole"))
                .findFirst()
                .orElseThrow();

        Pattern rolePattern = AuthController.LoginRequest.class
                .getDeclaredMethod(expectedRole.getName())
                .getAnnotation(Pattern.class);
        assertThat(rolePattern).isNotNull();
        assertThat(rolePattern.regexp())
                .isEqualTo("STUDENT|GUARDIAN|TEACHER|SCHOOL_ADMIN");
    }

    @Test
    void scheduleAttendanceAndLeaveWritesHaveDistinctOwners() throws Exception {
        Method generate = TeachingController.class.getDeclaredMethod(
                "generateSessions", long.class);
        Method updateSession = TeachingController.class.getDeclaredMethod(
                "updateSession", long.class, TeachingController.SessionRequest.class);
        Method saveAttendance = TeachingController.class.getDeclaredMethod(
                "saveAttendance", long.class, TeachingController.AttendanceBatch.class);
        Method reviewLeave = LeaveCorrectionController.class.getDeclaredMethod(
                "reviewLeave", long.class, LeaveCorrectionController.LeaveReview.class);

        assertThat(generate.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('SCHOOL_ADMIN')");
        assertThat(updateSession.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('TEACHER')");
        assertThat(saveAttendance.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('TEACHER')");
        assertThat(reviewLeave.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('TEACHER')");
        assertThat(TeachingController.SessionRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("notes");
    }
}
