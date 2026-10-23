package com.projtimeline.file;

import com.projtimeline.common.BusinessException;
import com.projtimeline.data.DataAccess;
import com.projtimeline.security.CurrentUser;
import com.projtimeline.security.PermissionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档与附件的读写口径，支持项目级 / 模块级 / 工作项级 / 任务级四层归属。
 * <p>
 * 权限：上传与删除按归属层级判定——项目级由 PM（或 ADMIN）、模块级由模块负责人、工作项级由工作项负责人、
 * 任务级由任务执行人或其上级管理者；读取统一走 {@code requireProjectRead}，
 * 因此 VIEWER 与项目成员只能下载，归档项目只读。删除为软删除，磁盘文件保留以维持"痕迹"。
 */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    /** 列表与汇总共用的列清单，避免两处 SELECT 漂移；调用方需保证 u（users）与 a（attachments）别名可用。 */
    private static final String BASE_COLUMNS = """
            a.id, a.project_id, a.owner_type, a.owner_id, a.category, a.title, a.original_name,
            a.content_type, a.size_bytes, a.sha256, a.version, a.remark, a.uploader_id,
            u.name AS uploader_name, a.created_at""";
    private static final String LIST_SQL = "SELECT " + BASE_COLUMNS + "\nFROM attachments a LEFT JOIN users u ON u.id = a.uploader_id\n";

    private final DataAccess db;
    private final PermissionService permissions;
    private final FileStorage storage;
    private final StorageProperties properties;

    public AttachmentService(DataAccess db, PermissionService permissions, FileStorage storage, StorageProperties properties) {
        this.db = db;
        this.permissions = permissions;
        this.storage = storage;
        this.properties = properties;
    }

    public List<DocumentCatalog.Category> categories() {
        return DocumentCatalog.all();
    }

    public List<Map<String, Object>> list(String ownerType, long ownerId) {
        String type = normalizeOwnerType(ownerType);
        permissions.requireProjectRead(projectOf(type, ownerId));
        return decorate(db.list(LIST_SQL + """
                WHERE a.owner_type=:type AND a.owner_id=:owner AND a.deleted=0
                ORDER BY a.category, a.created_at DESC, a.id DESC
                """, Map.of("type", type, "owner", ownerId)));
    }

    /**
     * 项目下的全部文件（项目级 + 模块级 + 工作项级 + 任务级），带归属名称，供项目文档页汇总查看。
     */
    public List<Map<String, Object>> listByProject(long projectId) {
        permissions.requireProjectRead(projectId);
        return decorate(db.list("SELECT " + BASE_COLUMNS + """
                ,
                       CASE a.owner_type
                         WHEN 'PROJECT' THEN p.name
                         WHEN 'MODULE' THEN m.name
                         WHEN 'NODE' THEN n.name
                         WHEN 'TASK' THEN t.name
                       END AS owner_name
                FROM attachments a
                JOIN projects p ON p.id = a.project_id
                LEFT JOIN modules m ON a.owner_type = 'MODULE' AND m.id = a.owner_id
                LEFT JOIN nodes n ON a.owner_type = 'NODE' AND n.id = a.owner_id
                LEFT JOIN tasks t ON a.owner_type = 'TASK' AND t.id = a.owner_id
                LEFT JOIN users u ON u.id = a.uploader_id
                WHERE a.project_id = :project AND a.deleted = 0
                ORDER BY a.created_at DESC, a.id DESC
                """, Map.of("project", projectId)));
    }

    public Map<String, Object> upload(String ownerType, long ownerId, String category, String title,
                                      String remark, MultipartFile file) {
        String type = normalizeOwnerType(ownerType);
        long projectId = projectOf(type, ownerId);
        if (DocumentCatalog.OWNER_TASK.equals(type)) permissions.requireTaskManager(ownerId, true);
        else if (DocumentCatalog.OWNER_NODE.equals(type)) permissions.requireNodeManager(ownerId);
        else if (DocumentCatalog.OWNER_MODULE.equals(type)) permissions.requireModuleManager(ownerId);
        else permissions.requireProjectManager(projectId);

        if (file == null || file.isEmpty()) throw BusinessException.badRequest("请选择要上传的文件");
        if (file.getSize() > properties.maxFileBytes()) {
            throw BusinessException.badRequest("文件超过 " + (properties.maxFileBytes() / 1024 / 1024) + "MB 上限");
        }
        String originalName = safeName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!properties.allows(extension)) {
            throw BusinessException.badRequest("不支持的文件类型：" + (extension == null ? "无扩展名" : extension));
        }
        DocumentCatalog.Category target = DocumentCatalog.find(type, category);
        if (target == null) throw BusinessException.badRequest("未知的文档分类：" + category);
        String docTitle = title == null || title.isBlank() ? originalName : title.trim();
        if (docTitle.length() > 200) throw BusinessException.badRequest("文件标题不能超过 200 字");
        String note = remark == null || remark.isBlank() ? null : remark.trim();
        if (note != null && note.length() > 500) throw BusinessException.badRequest("说明不能超过 500 字");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        String storedKey;
        try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
            storedKey = storage.put(in, file.getSize(), extension);
        } catch (IOException ex) {
            throw BusinessException.badRequest("文件读取失败");
        }
        long id;
        try {
            // 版本号对已软删除的记录同样占位、不复用，避免与唯一键 uk_attachment_version 冲突
            long previous = db.count("""
                    SELECT COALESCE(MAX(version),0) FROM attachments
                    WHERE owner_type=:type AND owner_id=:owner AND title=:title
                    """, Map.of("type", type, "owner", ownerId, "title", docTitle));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("project", projectId);
            params.put("type", type);
            params.put("owner", ownerId);
            params.put("category", category);
            params.put("title", docTitle);
            params.put("originalName", originalName);
            params.put("storedKey", storedKey);
            params.put("contentType", MediaTypes.of(extension));
            params.put("size", file.getSize());
            params.put("sha", HexFormat.of().formatHex(digest.digest()));
            params.put("version", previous + 1);
            params.put("remark", note);
            params.put("uploader", permissions.current().id());
            id = db.insert("""
                    INSERT INTO attachments(project_id,owner_type,owner_id,category,title,original_name,stored_key,
                                            content_type,size_bytes,sha256,version,remark,uploader_id,deleted)
                    VALUES(:project,:type,:owner,:category,:title,:originalName,:storedKey,
                           :contentType,:size,:sha,:version,:remark,:uploader,0)
                    """, params);
        } catch (RuntimeException ex) {
            try {
                storage.delete(storedKey);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Upload rollback failed for key {}: {}", storedKey, cleanupFailure.getMessage());
            }
            if (ex instanceof DataIntegrityViolationException) {
                throw BusinessException.conflict("同名文件正在上传，请稍后重试");
            }
            throw ex;
        }
        return decorate(List.of(db.one(LIST_SQL + " WHERE a.id=:id", Map.of("id", id), "附件不存在"))).get(0);
    }

    public AttachmentFile download(long id) {
        Map<String, Object> row = decorate(List.of(row(id))).get(0);
        permissions.requireProjectRead(PermissionService.number(row.get("projectId")));
        // 存储路径只在服务端使用，不出现在列表响应里
        Map<String, Object> stored = db.one("SELECT stored_key FROM attachments WHERE id=:id", Map.of("id", id), "附件不存在");
        return new AttachmentFile(row, storage.get((String) stored.get("storedKey")));
    }

    public void delete(long id) {
        Map<String, Object> row = row(id);
        long projectId = PermissionService.number(row.get("projectId"));
        permissions.requireProjectActive(projectId);
        CurrentUser user = permissions.current();
        if (PermissionService.number(row.get("uploaderId")) == user.id()) {
            db.update("UPDATE attachments SET deleted=1 WHERE id=:id", Map.of("id", id));
            return;
        }
        // 与上传口径一致：任务由执行人/工作项负责人管理，工作项由工作项负责人管理，模块由模块负责人管理，项目由项目经理管理
        String ownerType = String.valueOf(row.get("ownerType"));
        long ownerId = PermissionService.number(row.get("ownerId"));
        if (DocumentCatalog.OWNER_TASK.equals(ownerType)) {
            permissions.requireTaskManager(ownerId, true);
        } else if (DocumentCatalog.OWNER_NODE.equals(ownerType)) {
            permissions.requireNodeManager(ownerId);
        } else if (DocumentCatalog.OWNER_MODULE.equals(ownerType)) {
            permissions.requireModuleManager(ownerId);
        } else if (!user.isAdmin()) {
            permissions.requireProjectManager(projectId);
        }
        db.update("UPDATE attachments SET deleted=1 WHERE id=:id", Map.of("id", id));
    }

    public record AttachmentFile(Map<String, Object> row, InputStream stream) {}

    private Map<String, Object> row(long id) {
        return db.one(LIST_SQL + " WHERE a.id=:id AND a.deleted=0", Map.of("id", id), "附件不存在");
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        rows.forEach(row -> {
            DocumentCatalog.Category category = DocumentCatalog.find(
                    String.valueOf(row.get("ownerType")), String.valueOf(row.get("category")));
            if (category != null) row.put("categoryName", category.name());
        });
        return rows;
    }

    private String normalizeOwnerType(String ownerType) {
        String type = ownerType == null ? "" : ownerType.trim().toUpperCase(Locale.ROOT);
        if (!DocumentCatalog.supports(type)) throw BusinessException.badRequest("ownerType 仅支持 PROJECT、MODULE、NODE 或 TASK");
        return type;
    }

    private long projectOf(String ownerType, long ownerId) {
        if (DocumentCatalog.OWNER_PROJECT.equals(ownerType)) {
            return PermissionService.number(db.one("SELECT id FROM projects WHERE id=:id", Map.of("id", ownerId), "项目不存在").get("id"));
        }
        if (DocumentCatalog.OWNER_TASK.equals(ownerType)) return permissions.projectForTask(ownerId);
        if (DocumentCatalog.OWNER_NODE.equals(ownerType)) return permissions.projectForNode(ownerId);
        return PermissionService.number(db.one("SELECT project_id FROM modules WHERE id=:id", Map.of("id", ownerId), "模块不存在").get("projectId"));
    }

    private static String safeName(String raw) {
        if (raw == null) return "未命名文件";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) return "未命名文件";
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
