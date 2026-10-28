package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FeatureDeliveryTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void ownerConfirmsDeliveryWithoutRewritingLegacyTasksOrInferringTheirProgress(){
        f.login(3,"USER");
        assertThat(f.work.node(101)).containsEntry("progress",0).containsEntry("legacyTaskCount",6L);
        assertThat(f.work.changeNodeStatus(101,"DONE","IN_PROGRESS")).containsEntry("progress",100).containsEntry("status","DONE");
        assertThat(f.jdbc.queryForObject("SELECT status FROM tasks WHERE id=1001",String.class)).isEqualTo("DOING");
        assertThat(f.work.node(101).get("legacyTaskCount")).isEqualTo(6L);
    }

    @Test void staleWorkItemStatusCannotCancelAnAlreadyDeliveredFeature(){
        f.login(3,"USER");
        assertThatThrownBy(()->f.work.changeNodeStatus(101,"DONE","NOT_STARTED")).isInstanceOf(BusinessException.class);
        f.work.changeNodeStatus(101,"DONE","IN_PROGRESS");
        assertThatThrownBy(()->f.work.changeNodeStatus(101,"CANCELLED","IN_PROGRESS")).isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT status FROM nodes WHERE id=101",String.class)).isEqualTo("DONE");
    }

    @Test void legacyTaskWritesCannotBypassBugSourceRulesOrConvertHistoryIntoBug(){
        f.login(3,"USER");
        assertThatThrownBy(()->f.work.createTask(101,Map.of("name","取消来源","kind","BUG","relatedNodeId",104L)))
                .isInstanceOf(BusinessException.class);
        Map<String,Object> bug=f.work.createTask(101,Map.of("name","旧客户端报告","kind","BUG","relatedNodeId",103L,"assigneeId",2L));
        assertThat(bug).containsEntry("nodeId",103L).containsEntry("originNodeId",103L).containsEntry("assigneeId",3L);
        assertThatThrownBy(()->f.work.updateTask(1002,Map.of("name","偷偷转换","kind","BUG","relatedNodeId",104L)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT kind FROM tasks WHERE id=1002",String.class)).isEqualTo("FEATURE");
    }

    @Test void deliveredFeatureCanReceiveBugAndFixDoesNotReopenTheFeature(){
        Map<String,Object> bug=f.work.reportBug(10,Map.of("name","大文件上传失败","relatedNodeId",103L));
        long id=((Number)bug.get("id")).longValue();
        assertThat(bug).containsEntry("nodeId",103L).containsEntry("originNodeId",103L).containsEntry("originNodeStatus","DONE");
        f.login(3,"USER");
        assertThat(f.work.taskStatus(id,"DOING","TODO")).containsEntry("status","DOING");
        assertThat(f.work.taskStatus(id,"DONE","DOING")).containsEntry("status","DONE");
        assertThat(f.jdbc.queryForObject("SELECT status FROM nodes WHERE id=103",String.class)).isEqualTo("DONE");
        assertThat(f.work.bugs(103)).hasSize(1);
    }

    @Test void bugEditKeepsAssociationAndKindAndUsesFreshExpectedStatus(){
        Map<String,Object> bug=f.work.reportBug(10,Map.of("name","问题","relatedNodeId",103L));
        long id=((Number)bug.get("id")).longValue(); f.login(3,"USER");
        assertThat(f.work.updateTask(id,Map.of("name","补充现象","kind","BUG","relatedNodeId",103L,"assigneeId",2L))).containsEntry("name","补充现象").containsEntry("assigneeId",2L);
        assertThatThrownBy(()->f.work.updateTask(id,Map.of("name","换来源","kind","BUG","relatedNodeId",101L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.work.updateTask(id,Map.of("name","换类型","kind","FEATURE","relatedNodeId",103L))).isInstanceOf(BusinessException.class);
        f.login(2,"USER"); f.work.taskStatus(id,"DOING","TODO");
        assertThatThrownBy(()->f.work.taskStatus(id,"DONE","TODO")).isInstanceOf(BusinessException.class);
    }

    @Test void cancelledOriginsRejectNewAndEditableBugsButKeepNotesReadable(){
        assertThatThrownBy(()->f.work.reportBug(10,Map.of("name","已取消","relatedNodeId",104L))).isInstanceOf(BusinessException.class);
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=104 WHERE id=1001");
        assertThatThrownBy(()->f.work.taskStatus(1001,"DONE","DOING")).isInstanceOf(BusinessException.class);
        f.login(3,"USER");
        assertThatThrownBy(()->f.work.updateTask(1001,Map.of("name","修改已取消问题","kind","BUG","relatedNodeId",104L))).isInstanceOf(BusinessException.class);
        assertThat(f.work.taskNotes(1001)).isEmpty();
    }

    @Test void oldRoutedBugUsesOriginalFeatureManagersInsteadOfStorageOwners(){
        f.node(105,10,null,2,"IN_PROGRESS","2026-11-02",false,"旧Bug池");
        f.jdbc.update("UPDATE tasks SET kind='BUG',node_id=105,related_node_id=103,assignee_id=1 WHERE id=1001");
        f.login(2,"USER");
        assertThatThrownBy(()->f.permissions.requireTaskManager(1001,false)).isInstanceOf(BusinessException.class);
        f.login(3,"USER"); f.permissions.requireTaskManager(1001,false);
        assertThat(f.work.bugs(103)).extracting(row->row.get("id")).contains(1001L);
        assertThat(f.work.taskStatus(1001,"DONE","DOING")).containsEntry("originNodeId",103L);
    }

    @Test void crossProjectRelatedIdCannotExposeNameOrGrantPermission(){
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=201 WHERE id=1001");
        Map<String,Object> bug=f.work.bugs(101).get(0);
        assertThat(bug).containsEntry("originNodeId",101L).containsEntry("originNodeName","订单接口开发");
        assertThat(bug.get("relatedNodeName")).isNull();
        f.login(3,"USER");
        assertThat(f.work.updateTask(1001,Map.of("name","保留旧关联修正说明","kind","BUG","relatedNodeId",201L)))
                .containsEntry("originNodeId",101L).containsEntry("relatedNodeName",null);
        f.login(4,"USER");
        assertThatThrownBy(()->f.permissions.requireTaskManager(1001,true)).isInstanceOf(BusinessException.class);
    }

    @Test void viewerArchiveAndUnrelatedMemberCannotMutateDeliveryOrBug(){
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=103 WHERE id=1001");
        f.login(5,"VIEWER");
        assertThatThrownBy(()->f.work.taskStatus(1001,"DONE","DOING")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.work.changeNodeStatus(101,"DONE","IN_PROGRESS")).isInstanceOf(BusinessException.class);
        f.login(3,"USER");f.jdbc.update("UPDATE projects SET status='ARCHIVED' WHERE id=10");
        assertThatThrownBy(()->f.work.taskStatus(1001,"DONE","DOING")).isInstanceOf(BusinessException.class);
        f.login(4,"USER");
        assertThatThrownBy(()->f.work.reportBug(10,Map.of("name","外部提交","relatedNodeId",103L))).isInstanceOf(BusinessException.class);
    }

    @Test void progressRecordIsNamedMarkdownFileAndKeepsAttachmentPlaceholders(){
        f.login(2,"USER");
        Map<String,Object> note=f.work.addTaskNote(1001,Map.of("title","修复步骤","content","## 复现\n\n![截图](attachment:1)\n\n[日志](attachment:2)"));
        assertThat(note).containsEntry("title","修复步骤.md");
        assertThat(note.get("content").toString()).contains("attachment:1").contains("attachment:2");
        assertThat(f.work.taskNotes(1001)).extracting(row->row.get("title")).containsExactly("修复步骤.md");
        assertThat(f.work.addTaskNote(1001,Map.of("content","未命名记录"))).containsEntry("title","进展记录.md");
        assertThat(f.work.taskNotes(1001)).extracting(row->row.get("title")).contains("进展记录.md","修复步骤.md");
        assertThatThrownBy(()->f.work.addTaskNote(1001,Map.of("title","截图/复现","content","x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.work.addTaskNote(1001,Map.of("title","长".repeat(118),"content","x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.work.addTaskNote(1001,Map.of("title","超长正文","content","x".repeat(20001)))).isInstanceOf(BusinessException.class);
        f.login(5,"VIEWER");
        assertThatThrownBy(()->f.work.addTaskNote(1001,Map.of("title","只读","content","x"))).isInstanceOf(BusinessException.class);
    }

    @Test void authorOrTaskManagerCanEditSavedProgressRecord(){
        f.login(2,"USER");
        long id=((Number)f.work.addTaskNote(1001,Map.of("title","初稿","content","第一版")).get("id")).longValue();
        Map<String,Object> edited=f.work.updateTaskNote(id,Map.of("title","修复步骤","content","## 第二版\n\n见 attachment:1"));
        assertThat(edited).containsEntry("title","修复步骤.md").containsEntry("content","## 第二版\n\n见 attachment:1")
                .containsEntry("updatedByName","执行人");
        assertThat(edited.get("updatedAt")).isNotNull();
        assertThat(f.work.taskNotes(1001)).hasSize(1).extracting(row->row.get("title")).containsExactly("修复步骤.md");
        // 只改正文时保留原记录名
        assertThat(f.work.updateTaskNote(id,Map.of("content","只改正文"))).containsEntry("title","修复步骤.md").containsEntry("content","只改正文");
        // 非作者的模块/工作项负责人可编辑，并记录最后编辑人
        f.login(3,"USER");
        Map<String,Object> byManager=f.work.updateTaskNote(id,Map.of("title","修复步骤","content","负责人补充"));
        assertThat(byManager).containsEntry("content","负责人补充").containsEntry("updatedByName","模块负责人");
        // 非项目成员与 VIEWER 不能编辑
        f.login(4,"USER");
        assertThatThrownBy(()->f.work.updateTaskNote(id,Map.of("title","越权","content","x"))).isInstanceOf(BusinessException.class);
        f.login(5,"VIEWER");
        assertThatThrownBy(()->f.work.updateTaskNote(id,Map.of("title","只读","content","x"))).isInstanceOf(BusinessException.class);
        // 标题/正文校验沿用新建口径
        f.login(2,"USER");
        assertThatThrownBy(()->f.work.updateTaskNote(id,Map.of("title","截图/复现","content","x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.work.updateTaskNote(id,Map.of("title","超长正文","content","x".repeat(20001)))).isInstanceOf(BusinessException.class);
        // 归档项目只读
        f.jdbc.update("UPDATE projects SET status='ARCHIVED' WHERE id=10");
        assertThatThrownBy(()->f.work.updateTaskNote(id,Map.of("title","归档修改","content","x"))).isInstanceOf(BusinessException.class);
    }
}
