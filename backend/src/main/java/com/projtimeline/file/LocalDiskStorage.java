package com.projtimeline.file;

import com.projtimeline.common.BusinessException;
import org.springframework.http.HttpStatus;
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
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException(5000, HttpStatus.INTERNAL_SERVER_ERROR, "文件写入失败");
        }
        if (size > 0 && !Files.exists(target)) throw new BusinessException(5000, HttpStatus.INTERNAL_SERVER_ERROR, "文件写入失败");
        return relative;
    }

    @Override
    public InputStream get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) throw BusinessException.notFound("文件不存在");
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw new BusinessException(5000, HttpStatus.INTERNAL_SERVER_ERROR, "文件读取失败");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new BusinessException(5000, HttpStatus.INTERNAL_SERVER_ERROR, "文件删除失败");
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) throw BusinessException.badRequest("文件 key 不能为空");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw BusinessException.badRequest("非法的文件路径");
        return target;
    }
}
