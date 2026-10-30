package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WorkItemRecordTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void workItemOwnerSavesNamedMarkdownRecordsNewestFirst(){
        f.login(3,"USER");
        Map<String,Object> first=f.nodeNotes.add(101,Map.of("title","设计说明","content","## 接口\n\n![图](attachment:7)"));
        assertThat(first).containsEntry("title","设计说明.md").containsEntry("authorName","模块负责人").containsEntry("nodeId",101L);
        f.nodeNotes.add(101,Map.of("content","联调结果"));
        assertThat(f.nodeNotes.list(101)).extracting(row->row.get("title")).containsExactly("进展记录.md","设计说明.md");
        assertThatThrownBy(()->f.nodeNotes.add(101,Map.of("title","a/b","content","x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.nodeNotes.add(101,Map.of("title","空正文"))).isInstanceOf(BusinessException.class);
    }

    @Test void onlyWorkItemManagersOrTheAuthorCanWriteAndViewersStayReadOnly(){
        f.login(2,"USER");
        assertThatThrownBy(()->f.nodeNotes.add(101,Map.of("content","普通成员"))).isInstanceOf(BusinessException.class);
        f.login(1,"USER");
        long id=((Number)f.nodeNotes.add(101,Map.of("title","PM 记录","content","初稿")).get("id")).longValue();
        f.login(3,"USER");
        assertThat(f.nodeNotes.update(id,Map.of("content","负责人补充"))).containsEntry("title","PM 记录.md")
                .containsEntry("content","负责人补充").containsEntry("updatedByName","模块负责人");
        f.login(5,"VIEWER");
        assertThat(f.nodeNotes.list(101)).hasSize(1);
        assertThatThrownBy(()->f.nodeNotes.update(id,Map.of("content","x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.nodeNotes.delete(id)).isInstanceOf(BusinessException.class);
        f.login(4,"USER");
        assertThatThrownBy(()->f.nodeNotes.list(101)).isInstanceOf(BusinessException.class);
        f.login(3,"USER");
        f.nodeNotes.delete(id);
        assertThat(f.nodeNotes.list(101)).isEmpty();
    }

    @Test void closedWorkItemsStillAcceptRecordsButArchivedProjectsDoNot(){
        f.login(3,"USER");
        assertThat(f.nodeNotes.add(103,Map.of("content","交付后复盘"))).containsEntry("nodeId",103L);
        assertThatThrownBy(()->f.nodeNotes.add(301,Map.of("content","归档"))).isInstanceOf(BusinessException.class);
    }
}
