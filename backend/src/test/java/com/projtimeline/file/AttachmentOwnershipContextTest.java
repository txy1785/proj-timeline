package com.projtimeline.file;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AttachmentOwnershipContextTest {
    private UxDatabaseFixture f;
    private AttachmentService service;
    @BeforeEach void setup() {
        f=new UxDatabaseFixture();
        f.jdbc.execute("CREATE TABLE attachments(id BIGINT PRIMARY KEY,project_id BIGINT,owner_type VARCHAR(20),owner_id BIGINT,category VARCHAR(40),title VARCHAR(200),original_name VARCHAR(200),content_type VARCHAR(100),size_bytes BIGINT,sha256 VARCHAR(64),version INT,remark VARCHAR(1000),uploader_id BIGINT,created_at TIMESTAMP,deleted BOOLEAN DEFAULT FALSE)");
        service=new AttachmentService(f.db,f.permissions,mock(FileStorage.class),new StorageProperties(null,0,null));
        f.jdbc.execute("INSERT INTO attachments(id,project_id,owner_type,owner_id,category,title,uploader_id) VALUES(1,10,'PROJECT',10,'PROJECT_OTHER','项目文件',1),(2,10,'MODULE',100,'MODULE_OTHER','模块文件',3),(3,10,'NODE',101,'NODE_OTHER','工作项文件',3),(4,10,'TASK',1001,'TASK_OTHER','任务文件',2),(5,20,'TASK',2001,'TASK_OTHER','私有文件',4),(6,10,'TASK',2001,'TASK_OTHER','失配历史元数据',1)");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void taskAndNodeFilesProvideOwningNodeWithoutPerFileClientLookups() {
        Map<Long,Map<String,Object>> rows=service.listByProject(10).stream().collect(Collectors.toMap(r->((Number)r.get("id")).longValue(),Function.identity()));
        assertThat(rows.get(1L)).containsEntry("ownerName","共享商城").containsEntry("nodeId",null);
        assertThat(rows.get(2L)).containsEntry("ownerName","订单").containsEntry("nodeId",null);
        assertThat(rows.get(3L)).containsEntry("ownerName","订单接口开发").containsEntry("nodeId",101L);
        assertThat(rows.get(4L)).containsEntry("ownerName","逾期任务").containsEntry("nodeId",101L);
        assertThat(rows).doesNotContainKey(5L);
    }

    @Test void deepLinkContextCannotExposeAnotherProjectsOwnerThroughMismatchedMetadata() {
        Map<String,Object> mismatch=service.listByProject(10).stream().filter(r->((Number)r.get("id")).longValue()==6L).findFirst().orElseThrow();
        assertThat(mismatch).containsEntry("ownerName",null).containsEntry("nodeId",null);
        assertThatThrownBy(()->service.listByProject(20)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
    }
}
