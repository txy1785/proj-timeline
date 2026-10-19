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
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) { this.service = service; }

    @GetMapping("/categories")
    ApiResponse<List<DocumentCatalog.Category>> categories() { return ApiResponse.ok(service.categories()); }

    @GetMapping
    ApiResponse<List<Map<String, Object>>> list(@RequestParam String ownerType, @RequestParam long ownerId) {
        return ApiResponse.ok(service.list(ownerType, ownerId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<Map<String, Object>> upload(@RequestParam String ownerType,
                                            @RequestParam long ownerId,
                                            @RequestParam String category,
                                            @RequestParam(required = false) String title,
                                            @RequestParam(required = false) String remark,
                                            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.upload(ownerType, ownerId, category, title, remark, file));
    }

    @GetMapping("/{id}/content")
    ResponseEntity<Resource> content(@PathVariable long id) {
        AttachmentService.AttachmentFile file = service.download(id);
        Map<String, Object> row = file.row();
        String name = String.valueOf(row.get("originalName"));
        String contentType = row.get("contentType") == null ? null : row.get("contentType").toString();
        long size = ((Number) row.get("sizeBytes")).longValue();
        boolean inline = contentType != null
                && (contentType.startsWith("image/") || "application/pdf".equals(contentType) || contentType.startsWith("text/"));
        MediaType mediaType;
        try {
            mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (Exception ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiName = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded)
                .body(new InputStreamResource(file.stream()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.ok(null); }
}
