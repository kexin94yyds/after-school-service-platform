package com.afterschool.platform.rectification;

import com.afterschool.platform.auth.CurrentUser;
import com.afterschool.platform.auth.PlatformPrincipal;
import com.afterschool.platform.audit.AuditTargetContext;
import com.afterschool.platform.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RectificationService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");

    private final RectificationMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final Path storageRoot;

    public RectificationService(
            RectificationMapper mapper,
            CurrentUser currentUser,
            Clock clock,
            @Value("${app.rectification.storage-path:./data/rectification-materials}")
                    String storagePath) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.clock = clock;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> issueNotice(
            long alertId,
            RectificationController.NoticeRequest request) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"REGULATOR".equals(principal.roleCode())) {
            throw ApiException.forbidden("只有监管账号可以下发整改通知");
        }
        Map<String, Object> alert = requireAlert(alertId, null);
        long schoolId = ((Number) alert.get("schoolId")).longValue();
        AuditTargetContext.setTargetSchoolId(schoolId);
        if ("CLOSED".equals(alert.get("status"))) {
            throw ApiException.conflict(
                    "ALERT_CLOSED", "已关闭预警不能重新下发整改通知");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!request.dueAt().isAfter(now)) {
            throw ApiException.badRequest(
                    "RECTIFICATION_DUE_INVALID", "整改截止时间必须晚于当前时间");
        }
        mapper.upsertNotice(
                schoolId,
                alertId,
                request.title().strip(),
                request.requirements().strip(),
                request.dueAt(),
                principal.id());
        mapper.updateAlertDeadline(alertId, schoolId, request.dueAt());
        mapper.insertAction(
                schoolId,
                alertId,
                alert.get("status").toString(),
                "ISSUE_NOTICE",
                request.requirements().strip(),
                principal.id(),
                principal.roleCode());
        return mapper.findNotice(alertId, null);
    }

    public Map<String, Object> notice(long alertId) {
        Long schoolId = schoolScope();
        requireAlert(alertId, schoolId);
        Map<String, Object> notice = mapper.findNotice(alertId, schoolId);
        if (notice == null) {
            throw ApiException.notFound("整改通知不存在");
        }
        return notice;
    }

    public List<Map<String, Object>> materials(long alertId) {
        Long schoolId = schoolScope();
        requireAlert(alertId, schoolId);
        return mapper.listMaterials(alertId, schoolId);
    }

    @Transactional
    public Map<String, Object> uploadMaterial(
            long alertId, MultipartFile file) {
        PlatformPrincipal principal = currentUser.principal();
        if (!"SCHOOL_ADMIN".equals(principal.roleCode())
                || principal.schoolId() == null) {
            throw ApiException.forbidden("只有学校管理员可以提交整改材料");
        }
        long schoolId = principal.schoolId();
        Map<String, Object> alert = requireAlert(alertId, schoolId);
        if (!List.of("ACKNOWLEDGED", "RECTIFYING", "RETURNED")
                .contains(alert.get("status").toString())) {
            throw ApiException.conflict(
                    "MATERIAL_UPLOAD_NOT_ALLOWED",
                    "当前整改状态不能上传材料");
        }
        Map<String, Object> notice = mapper.findNotice(alertId, schoolId);
        if (notice == null) {
            throw ApiException.conflict(
                    "RECTIFICATION_NOTICE_REQUIRED",
                    "监管人员下发整改通知后才能提交材料");
        }
        ValidatedFile validated = validate(file);
        String objectKey = schoolId + "/" + alertId + "/"
                + UUID.randomUUID() + validated.extension();
        Path target = resolveObject(objectKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, validated.bytes());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw ApiException.conflict(
                    "MATERIAL_STORAGE_FAILED", "整改材料未能安全保存");
        }
        deleteOnRollback(target);
        if (mapper.insertMaterial(
                        schoolId,
                        alertId,
                        ((Number) notice.get("id")).longValue(),
                        validated.originalName(),
                        objectKey,
                        validated.contentType(),
                        validated.bytes().length,
                        sha256(validated.bytes()),
                        principal.id())
                != 1) {
            throw ApiException.conflict(
                    "MATERIAL_METADATA_FAILED", "整改材料记录未能保存");
        }
        mapper.insertAction(
                schoolId,
                alertId,
                alert.get("status").toString(),
                "SUBMIT_MATERIAL",
                "提交整改材料：" + validated.originalName(),
                principal.id(),
                principal.roleCode());
        return mapper.findMaterialByObjectKey(objectKey, schoolId);
    }

    public MaterialDownload download(long materialId) {
        Long schoolId = schoolScope();
        Map<String, Object> material = mapper.findMaterial(materialId, schoolId);
        if (material == null) {
            throw ApiException.notFound("整改材料不存在或不在当前数据范围内");
        }
        Path path = resolveObject(material.get("objectKey").toString());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("整改材料文件不存在");
        }
        return new MaterialDownload(
                path,
                material.get("originalName").toString(),
                material.get("contentType").toString(),
                ((Number) material.get("sizeBytes")).longValue());
    }

    private Long schoolScope() {
        PlatformPrincipal principal = currentUser.principal();
        if ("REGULATOR".equals(principal.roleCode())) {
            return null;
        }
        if ("SCHOOL_ADMIN".equals(principal.roleCode())
                && principal.schoolId() != null) {
            return principal.schoolId();
        }
        throw ApiException.forbidden("当前角色不能访问整改材料");
    }

    private Map<String, Object> requireAlert(long alertId, Long schoolId) {
        Map<String, Object> alert = mapper.findAlert(alertId, schoolId);
        if (alert == null) {
            throw ApiException.notFound("预警不存在或不在当前数据范围内");
        }
        AuditTargetContext.setTargetSchoolId(
                ((Number) alert.get("schoolId")).longValue());
        return alert;
    }

    private ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_SIZE) {
            throw ApiException.badRequest(
                    "MATERIAL_FILE_INVALID",
                    "整改材料必须为不超过 10 MB 的 PDF、JPG 或 PNG 文件");
        }
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase();
        if (!CONTENT_TYPES.contains(contentType)) {
            throw ApiException.badRequest(
                    "MATERIAL_TYPE_INVALID", "整改材料仅支持 PDF、JPG 和 PNG");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw ApiException.badRequest(
                    "MATERIAL_FILE_INVALID", "整改材料无法读取");
        }
        if (!matchesMagic(contentType, bytes)) {
            throw ApiException.badRequest(
                    "MATERIAL_CONTENT_INVALID", "整改材料内容与文件类型不一致");
        }
        String rawName = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().replace('\\', '/');
        String original = rawName.substring(rawName.lastIndexOf('/') + 1).strip();
        original = original.replaceAll("[\\p{Cntrl}]", "_");
        if (original.isBlank()) {
            original = "material" + extension(contentType);
        }
        if (original.length() > 255) {
            original = original.substring(original.length() - 255);
        }
        return new ValidatedFile(original, contentType, extension(contentType), bytes);
    }

    private boolean matchesMagic(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "application/pdf" -> bytes.length >= 5
                    && bytes[0] == '%'
                    && bytes[1] == 'P'
                    && bytes[2] == 'D'
                    && bytes[3] == 'F'
                    && bytes[4] == '-';
            case "image/jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89
                    && bytes[1] == 'P'
                    && bytes[2] == 'N'
                    && bytes[3] == 'G'
                    && bytes[4] == 0x0d
                    && bytes[5] == 0x0a
                    && bytes[6] == 0x1a
                    && bytes[7] == 0x0a;
            default -> false;
        };
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> "";
        };
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Path resolveObject(String objectKey) {
        Path path = storageRoot.resolve(objectKey).normalize();
        if (!path.startsWith(storageRoot)) {
            throw ApiException.badRequest(
                    "MATERIAL_PATH_INVALID", "整改材料路径无效");
        }
        return path;
    }

    private void deleteOnRollback(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            deleteQuietly(target);
                        }
                    }
                });
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The database transaction still fails closed; orphan cleanup can
            // be handled by storage maintenance if the filesystem is unhealthy.
        }
    }

    private record ValidatedFile(
            String originalName,
            String contentType,
            String extension,
            byte[] bytes) {}

    public record MaterialDownload(
            Path path,
            String originalName,
            String contentType,
            long size) {}
}
