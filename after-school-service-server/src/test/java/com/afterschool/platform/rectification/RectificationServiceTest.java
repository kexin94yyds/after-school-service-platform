package com.afterschool.platform.rectification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class RectificationServiceTest {

    @TempDir Path storage;

    @Test
    void schoolUploadsValidatedPdfWithHashAndAuditAction() {
        RectificationMapper mapper = mock(RectificationMapper.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        PlatformPrincipal principal = mock(PlatformPrincipal.class);
        when(currentUser.principal()).thenReturn(principal);
        when(principal.roleCode()).thenReturn("SCHOOL_ADMIN");
        when(principal.schoolId()).thenReturn(3L);
        when(principal.id()).thenReturn(12L);
        when(mapper.findAlert(8, 3L)).thenReturn(Map.of(
                "id", 8L,
                "schoolId", 3L,
                "status", "RECTIFYING"));
        when(mapper.findNotice(8, 3L)).thenReturn(Map.of("id", 5L));
        when(mapper.insertMaterial(
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.eq(8L),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.eq("evidence.pdf"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq("application/pdf"),
                        org.mockito.ArgumentMatchers.eq(8L),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(12L)))
                .thenReturn(1);
        when(mapper.findMaterialByObjectKey(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(3L)))
                .thenReturn(Map.of("id", 20L, "originalName", "evidence.pdf"));
        RectificationService service = new RectificationService(
                mapper, currentUser, Clock.systemUTC(), storage.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "evidence.pdf",
                "application/pdf",
                "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        Map<String, Object> created = service.uploadMaterial(8, file);

        assertThat(created).containsEntry("id", 20L);
        verify(mapper).insertAction(
                3,
                8,
                "RECTIFYING",
                "SUBMIT_MATERIAL",
                "提交整改材料：evidence.pdf",
                12,
                "SCHOOL_ADMIN");
    }
}
