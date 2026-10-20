package com.projtimeline.file;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件媒体类型口径：由服务端按扩展名推导，不采用客户端 multipart 里声明的 Content-Type。
 * <p>
 * 只有图片和 PDF 允许内联预览；其余一律以附件下载，防止把用户上传内容当页面执行。
 */
public final class MediaTypes {
    private MediaTypes() {}

    public static final String DEFAULT = "application/octet-stream";

    /** 允许浏览器内联打开的媒体类型。 */
    private static final List<String> INLINE_TYPES = List.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp");

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("txt", "text/plain;charset=UTF-8"),
            Map.entry("md", "text/markdown;charset=UTF-8"),
            Map.entry("csv", "text/csv;charset=UTF-8"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("drawio", "application/vnd.jgraph.mxfile"),
            Map.entry("vsdx", "application/vnd.ms-visio.drawing"),
            Map.entry("mm", "application/x-freemind"),
            Map.entry("xmind", "application/x-xmind"),
            Map.entry("puml", "text/plain;charset=UTF-8")
    );

    public static String of(String extension) {
        if (extension == null) return DEFAULT;
        return BY_EXTENSION.getOrDefault(extension.toLowerCase(Locale.ROOT), DEFAULT);
    }

    public static boolean inlineAllowed(String contentType) {
        return contentType != null && INLINE_TYPES.contains(contentType);
    }
}
