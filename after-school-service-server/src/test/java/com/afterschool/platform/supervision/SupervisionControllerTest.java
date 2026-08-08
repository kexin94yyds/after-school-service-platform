package com.afterschool.platform.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class SupervisionControllerTest {

    @Test
    void manualScanAndScanRunHistoryRemainRegulatorOnly() throws Exception {
        PreAuthorize classAuthorization = SupervisionController.class
                .getAnnotation(PreAuthorize.class);
        Method manualScan = SupervisionController.class.getDeclaredMethod(
                "scan", SupervisionController.ScanRequest.class);
        Method scanRuns = SupervisionController.class.getDeclaredMethod(
                "scanRuns", Integer.class);

        assertThat(classAuthorization.value())
                .isEqualTo("hasAnyRole('REGULATOR','SCHOOL_ADMIN')");
        assertThat(manualScan.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('REGULATOR')");
        assertThat(scanRuns.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('REGULATOR')");
    }
}
