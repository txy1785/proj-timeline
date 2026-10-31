package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 一条交付记录关联多个工作项文件（Flyway V16、#60–#63）。 */
class DeliverableAttachmentTest {
    /** 交付要求只能在「未开始」时约定与增删，清单用例先建这个未开始的工作项。 */
    private static final long NOT_STARTED_NODE = 105;
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void deliverableReturnsAttachmentArrayWithTitlesAndNoLegacySingleFileFields(){
        f.login(3,"USER");
        long node=notStartedNode();
        long api=f.nodeAttachment(node,10,"接口文档 v1","api-v1.pdf");
        long log=f.nodeAttachment(node,10,"联调记录","debug.log");
        Map<String,Object> created=f.work.createDeliverable(node,Map.of("name","接口文档","attachmentIds",List.of(api,log)));
        assertThat(created).containsKeys("id","nodeId","projectId","name","status","sortOrder","attachments","createdAt","updatedAt");
        assertThat(created).doesNotContainKeys("attachmentId","attachmentTitle","attachmentOriginalName","attachmentDeleted");
        assertThat(created).containsEntry("nodeId",node).containsEntry("status","WANTED");
        assertThat(created.get("attachments")).asList().hasSize(2);
        assertThat(titles(created)).containsExactly("接口文档 v1","联调记录");
        assertThat(firstAttachment(created)).containsKeys("id","title","originalName","contentType","sizeBytes","version","deleted")
                .containsEntry("originalName","api-v1.pdf").containsEntry("deleted",0);
        // 列表接口同样带 attachments，且一条交付物只占一行（不因关联多文件被放大）
        List<Map<String,Object>> listed=f.work.deliverables(node);
        assertThat(listed).hasSize(1);
        assertThat(ids(listed.get(0))).containsExactly(api,log);
        // 文件软删除后关联保留，读取时该元素 deleted=1
        f.jdbc.update("UPDATE attachments SET deleted=TRUE WHERE id=?",log);
        Map<String,Object> afterDelete=f.work.deliverables(node).get(0);
        assertThat(titles(afterDelete)).containsExactly("接口文档 v1","联调记录");
        assertThat(attachments(afterDelete).get(1)).containsEntry("deleted",1);
    }

    @Test void editKeepsReplacesAndClearsTheWholeAttachmentSet(){
        f.login(3,"USER");
        long node=notStartedNode();
        long first=f.nodeAttachment(node,10,"设计说明","design.md");
        long second=f.nodeAttachment(node,10,"接口示例","api.json");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付说明","attachmentIds",List.of(first,second))));
        // 不传 attachmentIds：保留原有全部关联
        Map<String,Object> kept=f.work.updateDeliverable(id,Map.of("remark","调整备注"));
        assertThat(kept).containsEntry("remark","调整备注");
        assertThat(ids(kept)).containsExactly(first,second);
        // 传 1 个：替换成 1 个
        Map<String,Object> one=f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(first)));
        assertThat(ids(one)).containsExactly(first);
        // 传空数组：清空全部关联，attachments 仍是数组而不是省略
        Map<String,Object> cleared=f.work.updateDeliverable(id,Map.of("attachmentIds",List.of()));
        assertThat(cleared.get("attachments")).asList().isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM node_deliverable_attachments WHERE deliverable_id=?",Long.class,id)).isZero();
        // 只改状态时关联原样保留
        assertThat(ids(f.work.updateDeliverable(id,Map.of("status","PROVIDED")))).isEmpty();
    }

    @Test void attachmentFromAnotherWorkItemIsRejected(){
        f.login(3,"USER");
        long node=notStartedNode();
        long mine=f.nodeAttachment(node,10,"本工作项文件","mine.pdf");
        long other=f.nodeAttachment(102,10,"他人工作项文件","other.pdf");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","attachmentIds",List.of(mine))));
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(other))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThatThrownBy(()->f.work.createDeliverable(node,Map.of("name","跨工作项","attachmentIds",List.of(other))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(mine);
    }

    @Test void closedWorkItemRejectsAttachmentSetChangesButStillAcceptsStatus(){
        f.login(3,"USER");
        long node=notStartedNode();
        long mine=f.nodeAttachment(node,10,"交付文件","mine.pdf");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","attachmentIds",List.of(mine))));
        f.work.changeNodeStatus(node,"IN_PROGRESS");
        f.work.changeNodeStatus(node,"DONE","IN_PROGRESS");
        long extra=f.nodeAttachment(node,10,"补充材料","extra.pdf");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(mine,extra))))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        // 已结束比进行中更严：备注也锁定
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("remark","事后补备注")))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        // 集合没变（即使回传）只改 status 仍然允许
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED","attachmentIds",List.of(mine))))
                .containsEntry("status","PROVIDED");
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(mine);
    }

    @Test void deletingDeliverableRemovesItsAttachmentLinksOnly(){
        f.login(3,"USER");
        long node=notStartedNode();
        long first=f.nodeAttachment(node,10,"甲","a.pdf");
        long second=f.nodeAttachment(node,10,"乙","b.pdf");
        long id=id(f.work.createDeliverable(node,Map.of("name","待删交付物","attachmentIds",List.of(first,second))));
        assertThat(linkCount(id)).isEqualTo(2);
        f.work.deleteDeliverable(id);
        assertThat(linkCount(id)).isZero();
        // 交付物删除不删文件记录
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM attachments",Long.class)).isEqualTo(2);
    }

    @Test void attachmentIdsInputIsTypeCheckedDedupedAndCapped(){
        f.login(3,"USER");
        long node=notStartedNode();
        long first=f.nodeAttachment(node,10,"设计说明","design.md");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","attachmentIds",List.of(first,first))));
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(first);
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds","不是数组")))
                .isInstanceOf(BusinessException.class).hasMessage("attachmentIds 必须是数组");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of("abc"))))
                .isInstanceOf(BusinessException.class).hasMessage("attachmentIds 必须是整数数组");
        List<Long> tooMany=new ArrayList<>();
        for(long i=1;i<=21;i++) tooMany.add(i*1000);
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",tooMany)))
                .isInstanceOf(BusinessException.class).hasMessage("一条交付记录最多关联 20 个文件");
    }

    @Test void decimalAttachmentIdIsRejectedAndKeepsLinksUnchanged(){
        f.login(3,"USER");
        long node=notStartedNode();
        long mine=f.nodeAttachment(node,10,"交付文件","mine.pdf");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","attachmentIds",List.of(mine))));
        // 小数不能被 longValue() 静默截断成整数 id（1.5 曾被当成 id=1），一律 400 且不动关联
        for(Object invalid:List.of(1.5d,1.5f,new BigDecimal("1.5"),new BigDecimal("1.5E+0"))){
            assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(invalid))))
                    .isInstanceOf(BusinessException.class).hasMessage("attachmentIds 必须是整数数组");
        }
        assertThat(linkCount(id)).isEqualTo(1);
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(mine);
        // 整数值的数值与纯整数字符串照旧接受
        assertThat(ids(f.work.updateDeliverable(id,Map.of("attachmentIds",List.of((double)mine))))).containsExactly(mine);
        assertThat(ids(f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(String.valueOf(mine)))))).containsExactly(mine);
    }

    @Test void softDeletedAttachmentResubmittedUnchangedStillPasses(){
        f.login(3,"USER");
        long node=notStartedNode();
        long mine=f.nodeAttachment(node,10,"交付文件","mine.pdf");
        long legacy=f.nodeAttachment(node,10,"遗留文件","legacy.log");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","attachmentIds",List.of(mine,legacy))));
        f.jdbc.update("UPDATE attachments SET deleted=TRUE WHERE id=?",legacy);
        // 已软删除的旧关联再次整表回传仍然成功：宽容分支只校验本次新提交的 id
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED","attachmentIds",List.of(mine,legacy))))
                .containsEntry("status","PROVIDED");
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(mine,legacy);
        // 但本次新提交一个不属于本工作项的文件仍然 400，旧关联的宽容不放大到新 id
        long foreign=f.nodeAttachment(102,10,"他人工作项文件","other.pdf");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(mine,legacy,foreign))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThat(ids(f.work.deliverables(node).get(0))).containsExactly(mine,legacy);
    }

    /** ① 交付要求（node_deliverables）在创建工作项时一次提交、同一事务落库，顺序与提交顺序一致。 */
    @Test void nodeCreationStoresRequirementsAsWantedDeliverablesInSubmittedOrder(){
        f.login(1,"USER");
        Map<String,Object> created=f.work.createNode(10,nodeBody(List.of("接口文档"," 联调记录 ","","上线检查单")));
        long node=((Number)created.get("id")).longValue();
        List<Map<String,Object>> requirements=f.work.deliverables(node);
        assertThat(requirements).hasSize(3);
        assertThat(requirements).extracting(row->String.valueOf(row.get("name"))).containsExactly("接口文档","联调记录","上线检查单");
        assertThat(requirements).extracting(row->((Number)row.get("sortOrder")).intValue()).containsExactly(0,1,2);
        assertThat(requirements).allSatisfy(row->{
            assertThat(row).containsEntry("status","WANTED");
            // 负责人与创建人继承工作项，备注为空
            assertThat(row.get("ownerId")).isEqualTo(created.get("ownerId"));
            assertThat(row.get("remark")).isNull();
        });
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM node_deliverables WHERE node_id=? AND created_by=1",Long.class,node)).isEqualTo(3);
        // 名单只写一次，不逐条补 DELIVERABLE_ADD；CREATE 日志里补条数
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM node_logs WHERE node_id=? AND action='DELIVERABLE_ADD'",Long.class,node)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT after_value FROM node_logs WHERE node_id=? AND action='CREATE'",String.class,node))
                .contains("\"requirements\":3");
    }

    /** requirements 的三种拒绝口径，且校验发生在写入工作项之前；不传该字段时不写任何交付要求。 */
    @Test void nodeRequirementsAreValidatedBeforeAnythingIsWritten(){
        f.login(1,"USER");
        assertThatThrownBy(()->f.work.createNode(10,nodeBody("不是数组")))
                .isInstanceOf(BusinessException.class).hasMessage("requirements 必须是数组");
        List<String> tooMany=new ArrayList<>();
        for(int index=1;index<=21;index++) tooMany.add("要求 "+index);
        assertThatThrownBy(()->f.work.createNode(10,nodeBody(tooMany)))
                .isInstanceOf(BusinessException.class).hasMessage("一个工作项最多约定 20 条交付要求");
        assertThatThrownBy(()->f.work.createNode(10,nodeBody(List.of("正常要求","超".repeat(201)))))
                .isInstanceOf(BusinessException.class).hasMessage("交付要求名称不能超过 200 字");
        // 非文本元素不再被 toString() 落成名称（[{a=1}]、true、null 一律 400）
        assertThatThrownBy(()->f.work.createNode(10,nodeBody(List.of(Map.of("a",1)))))
                .isInstanceOf(BusinessException.class).hasMessage("交付要求名称必须是文本");
        assertThatThrownBy(()->f.work.createNode(10,nodeBody(List.of(true))))
                .isInstanceOf(BusinessException.class).hasMessage("交付要求名称必须是文本");
        assertThatThrownBy(()->f.work.createNode(10,nodeBody(java.util.Arrays.asList((Object)null))))
                .isInstanceOf(BusinessException.class).hasMessage("交付要求名称必须是文本");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nodes WHERE name=?",Long.class,"约定交付要求")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM node_deliverables",Long.class)).isZero();
        assertThat(f.work.deliverables(((Number)f.work.createNode(10,withoutRequirements()).get("id")).longValue())).isEmpty();
    }

    /** ②③ 进行中改名、改派（以及改排序）一律 409，名单与归属保持不变。 */
    @Test void startedWorkItemRejectsRenamingReassigningAndReordering(){
        f.login(3,"USER");
        long node=notStartedNode();
        long id=id(f.work.createDeliverable(node,Map.of("name","接口文档","ownerId",3L)));
        f.work.changeNodeStatus(node,"IN_PROGRESS");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("name","接口文档 v2")))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已开始，交付要求只能更新状态、备注和关联文件");
        // 负责人 2 是项目内有效成员：409 来自"已开始"规则而不是负责人参数校验
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("ownerId",2L)))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已开始，交付要求只能更新状态、备注和关联文件");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("sortOrder",7)))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已开始，交付要求只能更新状态、备注和关联文件");
        assertThat(f.work.deliverables(node).get(0))
                .containsEntry("name","接口文档").containsEntry("ownerId",3L).containsEntry("sortOrder",0);
    }

    /** ④⑤ 进行中不能追加、不能删除交付要求。 */
    @Test void startedWorkItemRejectsAddingAndDeletingRequirements(){
        f.login(3,"USER");
        long node=notStartedNode();
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物")));
        f.work.changeNodeStatus(node,"IN_PROGRESS");
        assertThatThrownBy(()->f.work.createDeliverable(node,Map.of("name","开工后追加")))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已开始，不能新增交付要求");
        assertThatThrownBy(()->f.work.deleteDeliverable(id))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已开始，不能删除交付要求");
        assertThat(f.work.deliverables(node)).hasSize(1);
    }

    /** ⑥ 进行中仍可改状态、备注与关联文件（单改与一起改都允许）。 */
    @Test void startedWorkItemStillAcceptsStatusRemarkAndAttachments(){
        f.login(3,"USER");
        long node=notStartedNode();
        long first=f.nodeAttachment(node,10,"首个文件","first.pdf");
        long second=f.nodeAttachment(node,10,"补充文件","second.pdf");
        long id=id(f.work.createDeliverable(node,Map.of("name","接口文档","attachmentIds",List.of(first))));
        f.work.changeNodeStatus(node,"IN_PROGRESS");
        assertThat(f.work.updateDeliverable(id,Map.of("remark","资料在共享盘 /doc")))
                .containsEntry("remark","资料在共享盘 /doc");
        assertThat(ids(f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(first,second)))))
                .containsExactly(first,second);
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED"))).containsEntry("status","PROVIDED");
        Map<String,Object> together=f.work.updateDeliverable(id,Map.of("status","WANTED","remark","回退","attachmentIds",List.of(second)));
        assertThat(together).containsEntry("status","WANTED").containsEntry("remark","回退");
        assertThat(ids(together)).containsExactly(second);
    }

    /** 新增交付要求未指定 sortOrder 时排到末尾：列表 (sort_order, id) 升序必须等于创建顺序。 */
    @Test void createdRequirementWithoutSortOrderIsAppendedToTheEnd(){
        f.login(1,"USER");
        long node=((Number)f.work.createNode(10,nodeBody(List.of("甲","乙","丙"))).get("id")).longValue();
        // 不传 sortOrder：取当前最大值 +1，落到第 4 位而不是 0
        Map<String,Object> added=f.work.createDeliverable(node,Map.of("name","丁"));
        assertThat(added).containsEntry("sortOrder",3);
        List<Map<String,Object>> listed=f.work.deliverables(node);
        assertThat(listed).extracting(row->String.valueOf(row.get("name"))).containsExactly("甲","乙","丙","丁");
        assertThat(listed).extracting(row->((Number)row.get("sortOrder")).intValue()).containsExactly(0,1,2,3);
        // 新增排在末尾：id 比前三条都大
        long addedId=id(listed.get(3));
        assertThat(addedId).isEqualTo(id(added)).isGreaterThan(id(listed.get(2)));
        // 显式传 sortOrder 时仍以请求为准（9 排在最末，不再被改写成最大值 +1）
        assertThat(f.work.createDeliverable(node,Map.of("name","戊","sortOrder",9))).containsEntry("sortOrder",9);
        assertThat(f.work.deliverables(node)).extracting(row->String.valueOf(row.get("name"))).containsExactly("甲","乙","丙","丁","戊");
    }

    /** 未开始档的放行侧：改名、改派（项目内有效成员）、改排序都成功且返回值生效。 */
    @Test void notStartedWorkItemAcceptsRenameReassignAndReorder(){
        f.login(3,"USER");
        long node=notStartedNode();
        long id=id(f.work.createDeliverable(node,Map.of("name","接口文档","ownerId",3L)));
        assertThat(f.work.updateDeliverable(id,Map.of("name","接口文档 v2"))).containsEntry("name","接口文档 v2");
        assertThat(f.work.updateDeliverable(id,Map.of("ownerId",2L))).containsEntry("ownerId",2L);
        assertThat(f.work.updateDeliverable(id,Map.of("sortOrder",7))).containsEntry("sortOrder",7);
        assertThat(f.work.deliverables(node).get(0))
                .containsEntry("name","接口文档 v2").containsEntry("ownerId",2L).containsEntry("sortOrder",7);
    }

    /** ③ 已取消与已完成同档：只允许改状态，改名/改派/改排序一律 409。 */
    @Test void cancelledWorkItemOnlyAcceptsStatus(){
        f.login(3,"USER");
        long node=NOT_STARTED_NODE+1;
        f.node(node,10,100L,3,"NOT_STARTED","2026-10-28",false,"已取消交付要求工作项");
        long id=id(f.work.createDeliverable(node,Map.of("name","交付物","ownerId",3L)));
        f.work.changeNodeStatus(node,"CANCELLED");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("name","事后改名")))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("ownerId",2L)))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("sortOrder",5)))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED"))).containsEntry("status","PROVIDED");
        assertThat(f.work.deliverables(node).get(0))
                .containsEntry("name","交付物").containsEntry("ownerId",3L).containsEntry("sortOrder",0);
    }

    /** 项目级工作项（不传 moduleId）同样能一次约定交付要求，负责人默认取项目 PM。 */
    @Test void projectLevelWorkItemStoresRequirementsWithPmAsOwner(){
        f.login(1,"USER");
        Map<String,Object> created=f.work.createNode(10,projectLevelBody(List.of("立项材料","验收报告")));
        long node=((Number)created.get("id")).longValue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nodes WHERE id=? AND module_id IS NULL",Long.class,node)).isEqualTo(1);
        List<Map<String,Object>> requirements=f.work.deliverables(node);
        assertThat(requirements).extracting(row->String.valueOf(row.get("name"))).containsExactly("立项材料","验收报告");
        assertThat(requirements).extracting(row->((Number)row.get("sortOrder")).intValue()).containsExactly(0,1);
        // 未传 ownerId 时负责人取 PM（用户 1），与工作项同一事务落库
        assertThat(requirements).allSatisfy(row->{
            assertThat(row).containsEntry("status","WANTED");
            assertThat(((Number)row.get("ownerId")).longValue()).isEqualTo(1L);
        });
        assertThat(f.jdbc.queryForObject("SELECT owner_id FROM nodes WHERE id=?",Long.class,node)).isEqualTo(1L);
    }

    /** 未开始的工作项：开工前交付要求可增、可改、可删。 */
    private long notStartedNode(){
        f.node(NOT_STARTED_NODE,10,100L,3,"NOT_STARTED","2026-10-28",false,"交付要求工作项");
        return NOT_STARTED_NODE;
    }

    /** 创建工作项请求体：模块 100 的负责人是 3，负责人默认继承模块负责人。 */
    private static Map<String,Object> nodeBody(Object requirements){
        Map<String,Object> body=withoutRequirements();
        body.put("requirements",requirements);
        return body;
    }

    /** 项目级工作项的请求体：不传 moduleId，负责人由服务端取项目 PM。 */
    private static Map<String,Object> projectLevelBody(Object requirements){
        Map<String,Object> body=new HashMap<>();
        body.put("name","约定交付要求"); body.put("planStart","2026-10-26"); body.put("planEnd","2026-10-28");
        body.put("requirements",requirements);
        return body;
    }

    private static Map<String,Object> withoutRequirements(){
        Map<String,Object> body=new HashMap<>();
        body.put("moduleId",100); body.put("name","约定交付要求");
        body.put("planStart","2026-10-26"); body.put("planEnd","2026-10-28");
        return body;
    }

    private static long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
    private static List<Map<String,Object>> attachments(Map<String,Object> row){return asList(row.get("attachments"));}
    private static Map<String,Object> firstAttachment(Map<String,Object> row){return attachments(row).get(0);}
    private static List<String> titles(Map<String,Object> row){return attachments(row).stream().map(item->String.valueOf(item.get("title"))).toList();}
    private static List<Long> ids(Map<String,Object> row){return attachments(row).stream().map(item->((Number)item.get("id")).longValue()).toList();}
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> asList(Object value){return (List<Map<String,Object>>)value;}
    private long linkCount(long deliverableId){
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM node_deliverable_attachments WHERE deliverable_id=?",Long.class,deliverableId);
    }
}
