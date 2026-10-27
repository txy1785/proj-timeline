package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UxRoutingPermissionsTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup() { f=new UxDatabaseFixture(); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void routingPreflightIsReadOnlyAndAllowsActiveRelatedNodeWithoutBugfixPool() {
        long before=f.jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Long.class);
        f.jdbc.update("UPDATE nodes SET status='DONE' WHERE id=102");
        assertThat(f.work.bugRouting(10,101L)).containsEntry("canSubmit",true).containsEntry("nodeId",101L).containsEntry("ownerId",3L);
        assertThat(f.work.bugRouting(10,null)).containsEntry("canSubmit",false);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Long.class)).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM node_logs",Long.class)).isZero();
        verifyNoInteractions(f.notifications);
    }

    @Test void submissionReresolvesAfterPreflightAndKeepsRelationToClosedOriginal() {
        assertThat(f.work.bugRouting(10,101L)).containsEntry("nodeId",101L);
        f.jdbc.update("UPDATE nodes SET status='DONE' WHERE id=101");
        Map<String,Object> created=f.work.reportBug(10,Map.of("name","预判后状态变化","relatedNodeId",101L));
        assertThat(created).containsEntry("nodeId",102L).containsEntry("relatedNodeId",101L).containsEntry("kind","BUG").containsEntry("assigneeId",1L);
        verify(f.notifications).notify(eq(1L),eq("TASK_ASSIGN"),anyString(),eq("/nodes/102?task="+created.get("id")));
    }

    @Test void absentReceiverAndInvalidOrInaccessibleAssociationsHaveExplicitErrors() {
        assertThat(f.work.bugRouting(10,103L)).containsEntry("nodeId",102L);
        assertThatThrownBy(()->f.work.bugRouting(10,201L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4000));
        assertThatThrownBy(()->f.work.bugRouting(20,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        f.jdbc.update("UPDATE nodes SET status='DONE' WHERE id=102");
        assertThat(f.work.bugRouting(10,103L)).containsEntry("canSubmit",false);
        assertThatThrownBy(()->f.work.reportBug(10,Map.of("name","没有接收位置")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4090));
    }

    @Test void viewerArchivedProjectAndInactiveReceiverCannotSubmit() {
        f.login(5,"VIEWER");
        assertThatThrownBy(()->f.work.bugRouting(10,101L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        f.login(2,"USER");
        assertThatThrownBy(()->f.work.bugRouting(30,301L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4090));
        f.jdbc.update("UPDATE users SET active=FALSE WHERE id=3");
        assertThat(f.work.bugRouting(10,101L)).containsEntry("canSubmit",false);
        assertThatThrownBy(()->f.work.reportBug(10,Map.of("name","负责人停用","relatedNodeId",101L))).isInstanceOf(BusinessException.class);
    }

    @Test void moduleOwnerCanEditAndTransferButCannotCreateDeleteOrEditAfterTransfer() {
        f.login(3,"USER");
        assertThatThrownBy(()->f.projects.updateModule(100,Map.of("name","越权排序","ownerId",3L,"sortOrder",9)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        assertThat(f.projects.updateModule(100,Map.of("name","交易模块","ownerId",2L)))
                .containsEntry("name","交易模块").containsEntry("ownerId",2L);
        assertThatThrownBy(()->f.projects.updateModule(100,Map.of("name","旧负责人修改","ownerId",2L)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        assertThatThrownBy(()->f.projects.createModule(10,Map.of("name","新模块","ownerId",3L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.projects.deleteModule(100)).isInstanceOf(BusinessException.class);
    }

    @Test void moduleEditPreservesViewerArchiveAndAssignableBoundaries() {
        f.login(5,"VIEWER");
        assertThatThrownBy(()->f.projects.updateModule(100,Map.of("name","只读修改","ownerId",2L)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        f.login(3,"USER");
        assertThatThrownBy(()->f.projects.updateModule(100,Map.of("name","指派外部人","ownerId",4L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.projects.updateModule(300,Map.of("name","归档修改","ownerId",3L)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4090));
    }
}
