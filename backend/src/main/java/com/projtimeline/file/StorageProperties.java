package com.projtimeline.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * 文件存储配置。开发阶段使用本地磁盘；接入对象存储时只需替换 {@link FileStorage} 实现。
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String root, long maxFileBytes, List<String> allowedExtensions) {
    public static final List<String> DEFAULT_EXTENSIONS = List.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "md", "txt", "csv",
            "png", "jpg", "jpeg", "gif", "webp", "svg",
            "drawio", "vsdx", "mm", "xmind", "puml", "zip", "7z", "rar",
            "mp4", "mp3", "wav");

    public StorageProperties {
        if (root == null || root.isBlank()) root = "./data/files";
        if (maxFileBytes <= 0) maxFileBytes = 50L * 1024 * 1024;
        allowedExtensions = (allowedExtensions == null || allowedExtensions.isEmpty())
                ? DEFAULT_EXTENSIONS
                : allowedExtensions.stream().map(item -> item.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean allows(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }
}
