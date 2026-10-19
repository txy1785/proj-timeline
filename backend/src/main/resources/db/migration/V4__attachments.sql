SET NAMES utf8mb4;

CREATE TABLE attachments (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id    BIGINT UNSIGNED NOT NULL,
  owner_type    VARCHAR(16) NOT NULL COMMENT 'PROJECT / MODULE，预留 NODE / TASK',
  owner_id      BIGINT UNSIGNED NOT NULL,
  category      VARCHAR(40) NOT NULL COMMENT 'DocumentCatalog 分类编码',
  title         VARCHAR(200) NOT NULL COMMENT '文件标题，同名再上传时版本号递增',
  original_name VARCHAR(255) NOT NULL,
  stored_key    VARCHAR(255) NOT NULL COMMENT '存储相对路径，不含根目录',
  content_type  VARCHAR(160) NULL COMMENT '服务端按扩展名推导，不采用客户端声明',
  size_bytes    BIGINT NOT NULL DEFAULT 0,
  sha256        CHAR(64) NULL,
  version       INT NOT NULL DEFAULT 1,
  remark        VARCHAR(500) NULL,
  uploader_id   BIGINT UNSIGNED NOT NULL,
  deleted       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除，磁盘文件保留以维持痕迹',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attachment_version (owner_type, owner_id, title, version),
  KEY idx_attachment_owner (owner_type, owner_id, category),
  KEY idx_attachment_project (project_id, created_at),
  CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='项目与模块的文档、附件';
