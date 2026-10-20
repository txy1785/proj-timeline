package com.projtimeline.file;

import com.projtimeline.common.ApiResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文档与附件接口。下载走带 Authorization 头的一次性流式响应，不做 token 直链，权限每次请求重新校验。
 */
@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) { this.service = service; }

    @GetMapping("/attachments/categories")
    ApiResponse<List<DocumentCatalog.Category>> categories() { return ApiResponse.ok(service.categories()); }

    @GetMapping("/attachments")
    ApiResponse<List<Map<String, Object>>> list(@RequestParam String ownerType, @RequestParam long ownerId) {
        return ApiResponse.ok(service.list(ownerType, ownerId));
    }

    @GetMapping("/projects/{projectId}/attachments")
    ApiResponse<List<Map<String, Object>>> projectFiles(@PathVariable long projectId) {
        return ApiResponse.ok(service.listByProject(projectId));
    }

    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<Map<String, Object>> upload(@RequestParam String ownerType,
                                            @RequestParam long ownerId,
                                            @RequestParam String category,
                                            @RequestParam(required = false) String title,
                                            @RequestParam(required = false) String remark,
                                            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.upload(ownerType, ownerId, category, title, remark, file));
    }

    @GetMapping("/attachments/{id}/content")
    ResponseEntity<Resource> content(@PathVariable long id) {
        AttachmentService.AttachmentFile file = service.download(id);
        Map<String, Object> row = file.row();
        String name = String.valueOf(row.get("originalName"));
        String stored = row.get("contentType") == null ? null : row.get("contentType").toString();
        long size = ((Number) row.get("sizeBytes")).longValue();
        // 只有图片与 PDF 内联展示，其余一律强制下载，且媒体类型以服务端推导值为准
        boolean inline = MediaTypes.inlineAllowed(stored);
        MediaType mediaType = inline ? MediaType.parseMediaType(stored) : MediaType.APPLICATION_OCTET_STREAM;
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiName = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(file.stream()));
    }

    @DeleteMapping("/attachments/{id}")
    ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.ok(null); }
}
