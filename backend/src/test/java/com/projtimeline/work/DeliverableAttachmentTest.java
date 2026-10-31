package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 一条交付记录关联多个工作项文件（Flyway V16、#60–#63）。 */
class DeliverableAttachmentTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void deliverableReturnsAttachmentArrayWithTitlesAndNoLegacySingleFileFields(){
        f.login(3,"USER");
        long api=f.nodeAttachment(101,10,"接口文档 v1","api-v1.pdf");
        long log=f.nodeAttachment(101,10,"联调记录","debug.log");
        Map<String,Object> created=f.work.createDeliverable(101,Map.of("name","接口文档","attachmentIds",List.of(api,log)));
        assertThat(created).containsKeys("id","nodeId","projectId","name","status","sortOrder","attachments","createdAt","updatedAt");
        assertThat(created).doesNotContainKeys("attachmentId","attachmentTitle","attachmentOriginalName","attachmentDeleted");
        assertThat(created).containsEntry("nodeId",101L).containsEntry("status","WANTED");
        assertThat(created.get("attachments")).asList().hasSize(2);
        assertThat(titles(created)).containsExactly("接口文档 v1","联调记录");
        assertThat(firstAttachment(created)).containsKeys("id","title","originalName","contentType","sizeBytes","version","deleted")
                .containsEntry("originalName","api-v1.pdf").containsEntry("deleted",0);
        // 列表接口同样带 attachments，且一条交付物只占一行（不因关联多文件被放大）
        List<Map<String,Object>> listed=f.work.deliverables(101);
        assertThat(listed).hasSize(1);
        assertThat(ids(listed.get(0))).containsExactly(api,log);
        // 文件软删除后关联保留，读取时该元素 deleted=1
        f.jdbc.update("UPDATE attachments SET deleted=TRUE WHERE id=?",log);
        Map<String,Object> afterDelete=f.work.deliverables(101).get(0);
        assertThat(titles(afterDelete)).containsExactly("接口文档 v1","联调记录");
        assertThat(attachments(afterDelete).get(1)).containsEntry("deleted",1);
    }

    @Test void editKeepsReplacesAndClearsTheWholeAttachmentSet(){
        f.login(3,"USER");
        long first=f.nodeAttachment(101,10,"设计说明","design.md");
        long second=f.nodeAttachment(101,10,"接口示例","api.json");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付说明","attachmentIds",List.of(first,second))));
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
        long mine=f.nodeAttachment(101,10,"本工作项文件","mine.pdf");
        long other=f.nodeAttachment(102,10,"他人工作项文件","other.pdf");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付物","attachmentIds",List.of(mine))));
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(other))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThatThrownBy(()->f.work.createDeliverable(101,Map.of("name","跨工作项","attachmentIds",List.of(other))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(mine);
    }

    @Test void closedWorkItemRejectsAttachmentSetChangesButStillAcceptsStatus(){
        f.login(3,"USER");
        long mine=f.nodeAttachment(101,10,"交付文件","mine.pdf");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付物","attachmentIds",List.of(mine))));
        f.work.changeNodeStatus(101,"DONE","IN_PROGRESS");
        long extra=f.nodeAttachment(101,10,"补充材料","extra.pdf");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(mine,extra))))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("工作项已完成或已取消，只能更新交付物状态");
        // 集合没变（即使回传）只改 status 仍然允许
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED","attachmentIds",List.of(mine))))
                .containsEntry("status","PROVIDED");
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(mine);
    }

    @Test void deletingDeliverableRemovesItsAttachmentLinksOnly(){
        f.login(3,"USER");
        long first=f.nodeAttachment(101,10,"甲","a.pdf");
        long second=f.nodeAttachment(101,10,"乙","b.pdf");
        long id=id(f.work.createDeliverable(101,Map.of("name","待删交付物","attachmentIds",List.of(first,second))));
        assertThat(linkCount(id)).isEqualTo(2);
        f.work.deleteDeliverable(id);
        assertThat(linkCount(id)).isZero();
        // 交付物删除不删文件记录
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM attachments",Long.class)).isEqualTo(2);
    }

    @Test void attachmentIdsInputIsTypeCheckedDedupedAndCapped(){
        f.login(3,"USER");
        long first=f.nodeAttachment(101,10,"设计说明","design.md");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付物","attachmentIds",List.of(first,first))));
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(first);
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
        long mine=f.nodeAttachment(101,10,"交付文件","mine.pdf");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付物","attachmentIds",List.of(mine))));
        // 小数不能被 longValue() 静默截断成整数 id（1.5 曾被当成 id=1），一律 400 且不动关联
        for(Object invalid:List.of(1.5d,1.5f,new BigDecimal("1.5"),new BigDecimal("1.5E+0"))){
            assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(invalid))))
                    .isInstanceOf(BusinessException.class).hasMessage("attachmentIds 必须是整数数组");
        }
        assertThat(linkCount(id)).isEqualTo(1);
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(mine);
        // 整数值的数值与纯整数字符串照旧接受
        assertThat(ids(f.work.updateDeliverable(id,Map.of("attachmentIds",List.of((double)mine))))).containsExactly(mine);
        assertThat(ids(f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(String.valueOf(mine)))))).containsExactly(mine);
    }

    @Test void softDeletedAttachmentResubmittedUnchangedStillPasses(){
        f.login(3,"USER");
        long mine=f.nodeAttachment(101,10,"交付文件","mine.pdf");
        long legacy=f.nodeAttachment(101,10,"遗留文件","legacy.log");
        long id=id(f.work.createDeliverable(101,Map.of("name","交付物","attachmentIds",List.of(mine,legacy))));
        f.jdbc.update("UPDATE attachments SET deleted=TRUE WHERE id=?",legacy);
        // 已软删除的旧关联再次整表回传仍然成功：宽容分支只校验本次新提交的 id
        assertThat(f.work.updateDeliverable(id,Map.of("status","PROVIDED","attachmentIds",List.of(mine,legacy))))
                .containsEntry("status","PROVIDED");
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(mine,legacy);
        // 但本次新提交一个不属于本工作项的文件仍然 400，旧关联的宽容不放大到新 id
        long foreign=f.nodeAttachment(102,10,"他人工作项文件","other.pdf");
        assertThatThrownBy(()->f.work.updateDeliverable(id,Map.of("attachmentIds",List.of(mine,legacy,foreign))))
                .isInstanceOf(BusinessException.class).hasMessage("只能关联本工作项已上传的文件");
        assertThat(ids(f.work.deliverables(101).get(0))).containsExactly(mine,legacy);
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
