package com.projtimeline.file;

import java.io.InputStream;

/**
 * 文件存储抽象。开发阶段由 {@link LocalDiskStorage} 落本地磁盘；
 * 后续接入 MinIO/S3 只需新增实现并切换 {@code app.storage} 配置，业务代码与前端不受影响。
 */
public interface FileStorage {

    /**
     * 写入文件并返回存储 key（相对路径，不含根目录）。
     *
     * @param in          文件内容，调用方负责关闭
     * @param size        字节数，用于校验
     * @param extension   小写扩展名，可为 null
     */
    String put(InputStream in, long size, String extension);

    /**
     * 读取文件内容，调用方负责关闭。
     */
    InputStream get(String key);

    /**
     * 删除文件；文件不存在时视为成功。
     */
    void delete(String key);
}
