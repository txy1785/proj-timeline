package com.projtimeline.file;

import com.projtimeline.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskStorageTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private LocalDiskStorage storage(Path root) {
        return new LocalDiskStorage(new StorageProperties(root.toString(), 1024, null), CLOCK);
    }

    @Test
    void storesUnderDatedFolderAndReadsBack(@TempDir Path root) {
        LocalDiskStorage storage = storage(root);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String key = storage.put(new ByteArrayInputStream(content), content.length, "TXT");

        assertThat(key).startsWith("2026/10/").endsWith(".txt");
        assertThat(storage.get(key)).hasContent("hello");
    }

    @Test
    void rejectsPathTraversalAndAbsoluteKeys(@TempDir Path root) {
        LocalDiskStorage storage = storage(root);

        assertThatThrownBy(() -> storage.get("../../etc/passwd"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法的文件路径");
        assertThatThrownBy(() -> storage.get(root.getParent().resolve("outside.txt").toAbsolutePath().toString()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法的文件路径");
        assertThatThrownBy(() -> storage.get(""))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> storage.get(".."))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法的文件路径");
    }

    @Test
    void rejectsIncompleteWriteWhenDeclaredSizeMismatches(@TempDir Path root) {
        LocalDiskStorage storage = storage(root);
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content), 99, "txt"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("写入不完整");
    }

    @Test
    void deleteIsIdempotent(@TempDir Path root) {
        LocalDiskStorage storage = storage(root);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String key = storage.put(new ByteArrayInputStream(content), content.length, "txt");

        storage.delete(key);
        storage.delete(key);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(BusinessException.class).hasMessageContaining("文件不存在");
    }
}
