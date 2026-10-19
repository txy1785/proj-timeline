package com.projtimeline.file;

import com.projtimeline.common.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地磁盘实现：{root}/yyyy/MM/{uuid}.{ext}。路径解析后必须仍在根目录内，防止路径穿越。
 */
@Component
public class LocalDiskStorage implements FileStorage {
    private final Path root;
    private final Clock clock;

    public LocalDiskStorage(StorageProperties properties, Clock businessClock) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.clock = businessClock;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建文件存储目录: " + root, ex);
        }
    }

    @Override
    public String put(InputStream in, long size, String extension) {
        LocalDate today = LocalDate.now(clock);
        String relative = String.format(Locale.ROOT, "%04d/%02d/%s%s",
                today.getYear(), today.getMonthValue(), UUID.randomUUID(),
                extension == null || extension.isBlank() ? "" : "." + extension.toLowerCase(Locale.ROOT));
        Path target = resolve(relative);
        long written;
        try {
            Files.createDirectories(target.getParent());
            written = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { /* 交由运维清理残片 */ }
            throw BusinessException.internal("文件写入失败");
        }
        if (size > 0 && written != size) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { /* 磁盘异常交由运维清理 */ }
            throw BusinessException.badRequest("文件写入不完整，请重试");
        }
        return relative;
    }

    @Override
    public InputStream get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) throw BusinessException.notFound("文件不存在");
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw BusinessException.internal("文件读取失败");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw BusinessException.internal("文件删除失败");
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) throw BusinessException.badRequest("文件 key 不能为空");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw BusinessException.badRequest("非法的文件路径");
        return target;
    }
}
