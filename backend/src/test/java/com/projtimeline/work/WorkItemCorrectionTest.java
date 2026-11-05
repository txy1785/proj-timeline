package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Fixture clock is 2026-10-26T16:30Z in Asia/Shanghai, i.e. today = 2026-10-27. */
class WorkItemCorrectionTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){
        f=new UxDatabaseFixture(); f.login(1,"USER");
        f.jdbc.update("UPDATE nodes SET actual_start=DATE '2026-10-01',actual_end=DATE '2026-10-20' WHERE id=103");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void pmReopensDoneItemKeepingActualStartAndLogsReason(){
        f.work.reopenNode(103,body("expectedStatus","DONE","reason","验收发现遗漏"));
        assertThat(column(103,"status")).isEqualTo("IN_PROGRESS");
        assertThat(column(103,"actual_end")).isNull();
        assertThat(column(103,"actual_start")).isEqualTo(Date.valueOf("2026-10-01"));
        Map<String,Object> log=lastLog(103);
        assertThat(log.get("action")).isEqualTo("STATUS_CHANGE");
        assertThat(log.get("before_value").toString()).contains("DONE");
        assertThat(log.get("after_value").toString()).contains("IN_PROGRESS","验收发现遗漏");
    }

    @Test void pmCorrectsActualDatesAsHistorical(){
        f.work.correctActualDates(103,body("expectedStatus","DONE","actualStart",null,"actualEnd","2026-10-27","reason","补正上线日"));
        assertThat(column(103,"actual_start")).isNull();
        assertThat(column(103,"actual_end")).isEqualTo(Date.valueOf("2026-10-27"));
        assertThat(f.jdbc.queryForObject("SELECT historical FROM nodes WHERE id=103",Boolean.class)).isTrue();
        assertThat(lastLog(103).get("action")).isEqualTo("RESCHEDULE");
        assertThat(lastLog(103).get("after_value").toString()).contains("补正上线日");

        f.work.correctActualDates(101,body("expectedStatus","IN_PROGRESS","actualStart","2026-10-20","reason","补记开工"));
        assertThat(column(101,"actual_start")).isEqualTo(Date.valueOf("2026-10-20"));
    }

    @Test void invalidActualDatesAreRejected(){
        assertThat(code(()->f.work.correctActualDates(103,body("expectedStatus","DONE","actualEnd","2026-10-28","reason","未来")))).isEqualTo(4000);
        assertThat(code(()->f.work.correctActualDates(103,body("expectedStatus","DONE","actualStart","2026-10-21","actualEnd","2026-10-20","reason","倒序")))).isEqualTo(4000);
        assertThat(code(()->f.work.correctActualDates(101,body("expectedStatus","IN_PROGRESS","actualStart","2026-10-20","actualEnd","2026-10-21","reason","进行中有结束")))).isEqualTo(4000);
        assertThat(code(()->f.work.correctActualDates(104,body("expectedStatus","CANCELLED","actualEnd","2026-10-20","reason","已取消")))).isEqualTo(4000);
        assertThat(column(103,"actual_end")).isEqualTo(Date.valueOf("2026-10-20"));
    }

    @Test void reasonAndExpectedStateConflicts(){
        assertThat(code(()->f.work.reopenNode(103,body("expectedStatus","DONE")))).isEqualTo(4000);
        assertThat(code(()->f.work.reopenNode(103,body("expectedStatus","DONE","reason","x".repeat(501))))).isEqualTo(4000);
        assertThat(code(()->f.work.reopenNode(103,body("expectedStatus","IN_PROGRESS","reason","不合法")))).isEqualTo(4000);
        assertThat(code(()->f.work.reopenNode(101,body("expectedStatus","DONE","reason","已被别人改")))).isEqualTo(4090);
        assertThat(code(()->f.work.correctActualDates(101,body("expectedStatus","DONE","actualEnd","2026-10-20","reason","状态过期")))).isEqualTo(4090);
        assertThat(code(()->f.work.moveNode(101,body("moduleId",null,"reason","缺少期望")))).isEqualTo(4000);
        assertThat(code(()->f.work.moveNode(101,body("moduleId",null,"expectedModuleId",-1L,"reason","非法期望")))).isEqualTo(4000);
        assertThat(code(()->f.work.moveNode(101,body("moduleId",null,"expectedModuleId",null,"reason","模块已变")))).isEqualTo(4090);
        assertThat(column(103,"status")).isEqualTo("DONE");
        assertThat(((Number)column(101,"module_id")).longValue()).isEqualTo(100L);
    }

    @Test void moduleOwnerCannotReopenOrCorrectDates(){
        f.login(3,"USER");
        assertThat(code(()->f.work.reopenNode(103,body("expectedStatus","DONE","reason","越权")))).isEqualTo(4003);
        assertThat(code(()->f.work.correctActualDates(103,body("expectedStatus","DONE","actualEnd","2026-10-21","reason","越权")))).isEqualTo(4003);
        assertThat(column(103,"status")).isEqualTo("DONE");
        assertThat(code(()->f.work.moveNode(101,body("moduleId",null,"expectedModuleId",100L,"reason","项目级需PM")))).isEqualTo(4003);
    }

    @Test void crossProjectMoveIsRejected(){
        f.login(6,"ADMIN");
        assertThat(code(()->f.work.moveNode(101,body("moduleId",400L,"expectedModuleId",100L,"reason","跨项目")))).isEqualTo(4000);
        assertThat(((Number)column(101,"module_id")).longValue()).isEqualTo(100L);
    }

    @Test void moveOnlyChangesModuleAndPreservesOwnerAttachmentsBugsAndRecords(){
        long attachment=f.nodeAttachment(101,10,"设计稿","design.pdf");
        f.jdbc.update("INSERT INTO tasks(id,node_id,name,kind,related_node_id,assignee_id,status) VALUES(1100,101,'关联Bug','BUG',101,3,'TODO')");
        f.jdbc.update("INSERT INTO node_notes(node_id,title,content,author_id) VALUES(101,'记录.md','内容',1)");
        int tasks=countFor("tasks","node_id"), notes=countFor("node_notes","node_id");

        f.work.moveNode(101,body("moduleId",null,"expectedModuleId",100L,"reason","调整为项目级"));

        assertThat(column(101,"module_id")).isNull();
        assertThat(((Number)column(101,"owner_id")).longValue()).isEqualTo(3L);
        assertThat(column(101,"status")).isEqualTo("IN_PROGRESS");
        assertThat(f.jdbc.queryForObject("SELECT owner_id FROM attachments WHERE id=?",Long.class,attachment)).isEqualTo(101L);
        assertThat(f.jdbc.queryForObject("SELECT related_node_id FROM tasks WHERE id=1100",Long.class)).isEqualTo(101L);
        assertThat(countFor("tasks","node_id")).isEqualTo(tasks);
        assertThat(countFor("node_notes","node_id")).isEqualTo(notes);
        Map<String,Object> log=lastLog(101);
        assertThat(log.get("action")).isEqualTo("RESCHEDULE");
        assertThat(log.get("before_value").toString()).contains("\"moduleId\":100");
        assertThat(log.get("after_value").toString()).contains("\"moduleId\":null","调整为项目级");
    }

    private static Map<String,Object> body(Object... pairs){
        Map<String,Object> body=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2) body.put(pairs[i].toString(),pairs[i+1]);
        return body;
    }
    private Object column(long id,String column){return f.jdbc.queryForMap("SELECT "+column+" FROM nodes WHERE id=?",id).get(column);}
    private int countFor(String table,String column){return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=101",Integer.class);}
    private Map<String,Object> lastLog(long node){
        return f.jdbc.queryForMap("SELECT action,before_value,after_value FROM node_logs WHERE node_id=? ORDER BY id DESC LIMIT 1",node);
    }
    private static int code(ThrowingCallable call){
        Throwable thrown=catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException)thrown).code();
    }
}
