package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Fixture clock is 2026-10-26T16:30Z in Asia/Shanghai, i.e. today = 2026-10-27. */
class OptionalPlanningTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture(); f.login(1,"USER");}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void titleOnlyWorkItemHasNoPlanDatesAndStartsNotStarted(){
        long id=id(f.work.createNode(10,body("name","只有标题")));
        assertThat(column(id,"plan_start")).isNull();
        assertThat(column(id,"plan_end")).isNull();
        assertThat(column(id,"status")).isEqualTo("NOT_STARTED");
        assertThat(f.jdbc.queryForObject("SELECT historical FROM nodes WHERE id=?",Boolean.class,id)).isFalse();
    }

    @Test void explicitNullOwnerIsReadableAndNeverOverdue(){
        long id=id(f.work.createNode(10,body("name","待认领","ownerId",null)));
        assertThat(column(id,"owner_id")).isNull();
        f.login(2,"USER");
        Map<String,Object> node=f.work.node(id);
        assertThat(node).containsEntry("name","待认领");
        assertThat(node.get("ownerId")).isNull();
        f.login(1,"USER");
        assertThatThrownBy(()->f.work.overdueReason(id,"没有计划日期")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("未延期");
    }

    @Test void missingOrZeroOwnerFallsBackToModuleOwnerOrProjectManager(){
        assertThat(ownerOf(f.work.createNode(10,body("name","模块默认","moduleId",100L)))).isEqualTo(3L);
        assertThat(ownerOf(f.work.createNode(10,body("name","项目默认")))).isEqualTo(1L);
        assertThat(ownerOf(f.work.createNode(10,body("name","零值视为未传","ownerId",0L)))).isEqualTo(1L);
    }

    @Test void nullDateInPartialUpdateClearsOnlyThatFieldAndKeepsOmittedOnes(){
        long id=id(f.work.createNode(10,body("name","原名","description","原说明","moduleId",100L,
                "planStart","2026-10-20","planEnd","2026-11-10")));
        f.work.updateNode(id,body("planEnd",null));
        assertThat(column(id,"plan_end")).isNull();
        assertThat(column(id,"plan_start")).isEqualTo(java.sql.Date.valueOf("2026-10-20"));
        assertThat(column(id,"name")).isEqualTo("原名");
        assertThat(column(id,"description")).isEqualTo("原说明");
        assertThat(((Number)column(id,"owner_id")).longValue()).isEqualTo(3L);
    }

    @Test void inProgressWorkItemCannotClearOwner(){
        assertThat(code(()->f.work.updateNode(101,body("ownerId",null)))).isEqualTo(4000);
        assertThat(((Number)column(101,"owner_id")).longValue()).isEqualTo(3L);
    }

    @Test void historicalDoneKeepsPastActualEndWithoutPlanDates(){
        long id=id(f.work.createNode(10,body("name","补录上线","historical",true,"status","DONE",
                "actualStart","2026-09-20","actualEnd","2026-10-01")));
        assertThat(column(id,"status")).isEqualTo("DONE");
        assertThat(column(id,"actual_start")).isEqualTo(java.sql.Date.valueOf("2026-09-20"));
        assertThat(column(id,"actual_end")).isEqualTo(java.sql.Date.valueOf("2026-10-01"));
        assertThat(column(id,"plan_start")).isNull();
        assertThat(column(id,"plan_end")).isNull();
        assertThat(f.jdbc.queryForObject("SELECT historical FROM nodes WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(f.work.node(id)).containsEntry("status","DONE");
    }

    @Test void historicalActualDatesAfterTodayAreRejected(){
        int before=nodeCount();
        assertThat(code(()->f.work.createNode(10,body("name","未来完成","historical",true,"status","DONE","actualEnd","2026-10-28")))).isEqualTo(4000);
        assertThat(code(()->f.work.createNode(10,body("name","未来开始","historical",true,"status","IN_PROGRESS","actualStart","2026-10-28")))).isEqualTo(4000);
        assertThat(nodeCount()).isEqualTo(before);
        long today=id(f.work.createNode(10,body("name","今天完成","historical",true,"status","DONE","actualEnd","2026-10-27")));
        assertThat(column(today,"actual_end")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026,10,27)));
    }

    @Test void moduleOwnerWhoIsNotProjectManagerCannotBackfillHistory(){
        f.login(3,"USER");
        int before=nodeCount();
        assertThat(code(()->f.work.createNode(10,body("name","越权补录","moduleId",100L,"historical",true,
                "status","DONE","actualEnd","2026-10-01")))).isEqualTo(4003);
        assertThat(nodeCount()).isEqualTo(before);
        assertThat(ownerOf(f.work.createNode(10,body("name","普通创建","moduleId",100L)))).isEqualTo(3L);
    }

    @Test void viewerCannotCreateWorkItems(){
        f.login(5,"VIEWER");
        int before=nodeCount();
        assertThat(code(()->f.work.createNode(10,body("name","只读创建")))).isEqualTo(4003);
        assertThat(code(()->f.work.createNode(10,body("name","只读模块创建","moduleId",100L)))).isEqualTo(4003);
        assertThat(nodeCount()).isEqualTo(before);
    }

    @Test void bugOnUnassignedFeatureNeedsExplicitAssigneeAndPreflightExplains(){
        long feature=id(f.work.createNode(10,body("name","未指派功能","moduleId",100L,"ownerId",null)));
        f.login(2,"USER");
        Map<String,Object> preflight=f.work.bugRouting(10,feature);
        assertThat(preflight).containsEntry("canSubmit",false);
        assertThat(preflight.get("message").toString()).contains("未指派负责人");

        assertThatThrownBy(()->f.work.reportBug(10,body("name","无人接收","relatedNodeId",feature)))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code()).isEqualTo(4000))
                .hasMessageContaining("未指派负责人");
        assertThat(code(()->f.work.reportBug(10,body("name","零值接收人","relatedNodeId",feature,"assigneeId",0L)))).isEqualTo(4000);
        assertThatThrownBy(()->f.work.reportBug(10,body("name","非成员接收","relatedNodeId",feature,"assigneeId",7L)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE kind='BUG'",Integer.class)).isZero();

        Map<String,Object> bug=f.work.reportBug(10,body("name","指定接收","relatedNodeId",feature,"assigneeId",2L));
        assertThat(bug).containsEntry("assigneeId",2L).containsEntry("routedNodeId",feature);
    }

    @Test void assignedFeatureStillRoutesBugToItsOwnerIgnoringRequestedAssignee(){
        f.login(2,"USER");
        assertThat(f.work.bugRouting(10,103L)).containsEntry("canSubmit",true).containsEntry("ownerId",3L);
        assertThat(f.work.reportBug(10,body("name","常规路由","relatedNodeId",103L,"assigneeId",2L))).containsEntry("assigneeId",3L);
    }

    @Test void taskOnUnassignedWorkItemUsesExplicitAssigneeOrFailsWithClearMessage(){
        long node=id(f.work.createNode(10,body("name","未指派工作项","moduleId",100L,"ownerId",null)));
        assertThatThrownBy(()->f.work.createTask(node,body("name","无执行人")))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code()).isEqualTo(4000))
                .hasMessage("工作项未指派负责人，请指定任务执行人");
        assertThat(code(()->f.work.createTask(node,body("name","零值执行人","assigneeId",0L)))).isEqualTo(4000);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE node_id=?",Integer.class,node)).isZero();
        assertThat(f.work.createTask(node,body("name","指定执行人","assigneeId",2L))).containsEntry("assigneeId",2L);
    }

    private static Map<String,Object> body(Object... pairs){
        Map<String,Object> body=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2) body.put(pairs[i].toString(),pairs[i+1]);
        return body;
    }
    private static long id(Map<String,Object> node){return ((Number)node.get("id")).longValue();}
    private Long ownerOf(Map<String,Object> node){Object owner=column(id(node),"owner_id"); return owner==null?null:((Number)owner).longValue();}
    private Object column(long id,String column){return f.jdbc.queryForMap("SELECT "+column+" FROM nodes WHERE id=?",id).get(column);}
    private int nodeCount(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM nodes",Integer.class);}
    private static int code(ThrowingCallable call){
        Throwable thrown=catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException)thrown).code();
    }
}
