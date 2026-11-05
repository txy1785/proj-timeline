package com.projtimeline.work;

import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 历史补录的 CREATE 审计基线、同值纠错幂等，以及工作项写入口的事务隔离级别。 */
class UxAuditIsolationTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    /** H2 证明不了 MySQL 并发；这里钉住注解，避免有人改回默认隔离级别后锁后复核重新读到 RR 旧快照。 */
    @Test void everyNodeWriteEntrypointUsesReadCommitted() throws NoSuchMethodException {
        List<Method> entrypoints=List.of(
                WorkService.class.getMethod("createNode",long.class,Map.class),
                WorkService.class.getMethod("updateNode",long.class,Map.class),
                WorkService.class.getMethod("changeNodeStatus",long.class,String.class),
                WorkService.class.getMethod("changeNodeStatus",long.class,String.class,String.class),
                WorkService.class.getMethod("reopenNode",long.class,Map.class),
                WorkService.class.getMethod("correctActualDates",long.class,Map.class),
                WorkService.class.getMethod("moveNode",long.class,Map.class),
                WorkService.class.getMethod("createDeliverable",long.class,Map.class),
                WorkService.class.getMethod("updateDeliverable",long.class,Map.class),
                WorkService.class.getMethod("deleteDeliverable",long.class),
                WorkService.class.getMethod("deleteDeliverable",long.class,String.class));
        assertThat(entrypoints).hasSize(11).allSatisfy(method->{
            Transactional tx=method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.toString()).isNotNull();
            assertThat(tx.isolation()).as(method.toString()).isEqualTo(Isolation.READ_COMMITTED);
        });
    }

    @Test void historicalInProgressCreationLogsStatusDatesAndFullRequirementBaseline(){
        f.login(1,"USER");
        long node=create(historicalBody("IN_PROGRESS","2026-10-20",null,List.of("接口文档","联调记录")));
        Map<String,Object> after=createLog(node);
        assertThat(after).containsEntry("status","IN_PROGRESS").containsEntry("historical",true)
                .containsEntry("actualStart","2026-10-20").containsEntry("requirements",2);
        assertThat(after.get("actualEnd")).isNull();
        List<Map<String,Object>> baseline=baseline(after);
        List<Map<String,Object>> stored=f.work.deliverables(node);
        assertThat(baseline).hasSize(2);
        assertSnapshot(baseline.get(0),id(stored.get(0)),"接口文档",0);
        assertSnapshot(baseline.get(1),id(stored.get(1)),"联调记录",1);
    }

    @Test void historicalDoneCreationLogsUnknownStartActualEndAndBaseline(){
        f.login(1,"USER");
        long node=create(historicalBody("DONE",null,"2026-10-25",List.of("验收报告")));
        Map<String,Object> after=createLog(node);
        assertThat(after).containsEntry("status","DONE").containsEntry("historical",true)
                .containsEntry("actualEnd","2026-10-25").containsEntry("requirements",1);
        assertThat(after.get("actualStart")).isNull();
        List<Map<String,Object>> baseline=baseline(after);
        assertThat(baseline).hasSize(1);
        assertSnapshot(baseline.get(0),id(f.work.deliverables(node).get(0)),"验收报告",0);
    }

    /** 普通创建仍从未开始起步，基线由开工动作写入，CREATE 日志不重复携带。 */
    @Test void ordinaryCreationKeepsRequirementCountWithoutBaseline(){
        f.login(1,"USER");
        Map<String,Object> body=new HashMap<>();
        body.put("moduleId",100); body.put("name","普通工作项"); body.put("requirements",List.of("接口文档"));
        Map<String,Object> after=createLog(create(body));
        assertThat(after).containsEntry("status","NOT_STARTED").containsEntry("historical",false).containsEntry("requirements",1)
                .doesNotContainKey("requirementBaseline");
    }

    @Test void correctingActualDatesToSameValuesWritesNoExtraLog(){
        f.login(1,"USER");
        long node=create(historicalBody("DONE","2026-10-20","2026-10-25",List.of()));
        f.work.correctActualDates(node,correction("2026-10-20","2026-10-25"));
        assertThat(rescheduleLogs(node)).isZero();
        f.work.correctActualDates(node,correction("2026-10-21","2026-10-25"));
        assertThat(rescheduleLogs(node)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT actual_start FROM nodes WHERE id=?",java.sql.Date.class,node))
                .isEqualTo(java.sql.Date.valueOf("2026-10-21"));
    }

    private long create(Map<String,Object> body){return id(f.work.createNode(10,body));}

    /** 模块 100 的负责人 3 作为默认负责人；业务今天为 2026-10-27。 */
    private static Map<String,Object> historicalBody(String status,String actualStart,String actualEnd,List<String> requirements){
        Map<String,Object> body=new HashMap<>();
        body.put("moduleId",100); body.put("name","补录工作项"); body.put("historical",true); body.put("status",status);
        body.put("actualStart",actualStart); body.put("actualEnd",actualEnd); body.put("requirements",requirements);
        return body;
    }

    private static Map<String,Object> correction(String actualStart,String actualEnd){
        Map<String,Object> body=new HashMap<>();
        body.put("expectedStatus","DONE"); body.put("reason","按验收单核对");
        body.put("actualStart",actualStart); body.put("actualEnd",actualEnd);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> createLog(long node){
        Map<String,Object> log=f.work.logs(node).stream().filter(row->"CREATE".equals(row.get("action"))).findFirst().orElseThrow();
        return (Map<String,Object>)log.get("afterValue");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> baseline(Map<String,Object> after){
        assertThat(after).containsKey("requirementBaseline");
        return (List<Map<String,Object>>)after.get("requirementBaseline");
    }

    private long rescheduleLogs(long node){
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM node_logs WHERE node_id=? AND action='RESCHEDULE'",Long.class,node);
    }

    private static long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}

    private static void assertSnapshot(Map<String,Object> snapshot,long id,String name,int sort){
        assertThat(snapshot).containsKeys("deliverableId","name","status","ownerId","remark","sortOrder","attachmentIds");
        assertThat(((Number)snapshot.get("deliverableId")).longValue()).isEqualTo(id);
        assertThat(snapshot).containsEntry("name",name).containsEntry("status","WANTED");
        assertThat(((Number)snapshot.get("ownerId")).longValue()).isEqualTo(3L);
        assertThat(snapshot.get("remark")).isNull();
        assertThat(((Number)snapshot.get("sortOrder")).intValue()).isEqualTo(sort);
        assertThat((List<?>)snapshot.get("attachmentIds")).isEmpty();
    }
}
