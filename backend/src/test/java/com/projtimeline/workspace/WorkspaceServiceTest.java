package com.projtimeline.workspace;

import com.projtimeline.common.BusinessException;
import com.projtimeline.support.UxDatabaseFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup() { f = new UxDatabaseFixture(); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private WorkspaceTaskPage tasks(String scope) { return f.workspace.tasks(scope,null,null,null,null,1,20,null); }

    @Test void businessDateAndUnreadCountUseCurrentUserAndServerZone() {
        assertThat(f.workspace.context()).containsEntry("businessDate",LocalDate.of(2026,10,27))
                .containsEntry("timeZone","Asia/Shanghai").containsEntry("unreadCount",2L);
        f.login(1,"USER");
        assertThat(f.workspace.context()).containsEntry("unreadCount",1L);
    }

    @Test void personalOpenListExcludesOtherPeoplePrivateCancelledArchivedAndClosedTasks() {
        WorkspaceTaskPage page=tasks("OPEN");
        assertThat(page.list()).extracting(r->((Number)r.get("id")).longValue()).containsExactly(1001L,1002L,1003L,4001L,1010L,1004L);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.counts()).containsExactlyInAnyOrderEntriesOf(Map.of("open",6L,"doing",2L,"overdue",1L,"done",2L));
        assertThat(page.list().get(0)).containsEntry("overdue",true).containsEntry("nodeName","订单接口开发")
                .containsEntry("projectName","共享商城").containsEntry("projectStatus","ACTIVE");
        assertThat(page.list().get(1)).containsEntry("overdue",false);
    }

    @Test void doneIncludesCompletedParentsAndHistoryIsExplicit() {
        assertThat(tasks("DONE").list()).extracting(r->((Number)r.get("id")).longValue()).containsExactly(1005L,1020L);
        assertThat(tasks("HISTORY").total()).isEqualTo(12);
        assertThat(tasks("HISTORY").list()).extracting(r->((Number)r.get("id")).longValue()).doesNotContain(2001L,1006L);
    }

    @Test void workloadScopesMatchExistingCountsAndNaturalWeekIncludesArchivedReadableHistory() {
        assertThat(tasks("WORKLOAD").total()).isEqualTo(8);
        WorkspaceTaskPage due=f.workspace.tasks("WORKLOAD",null,null,2L,null,1,20,LocalDate.of(2026,10,27));
        assertThat(due.total()).isEqualTo(6);
        assertThat(due.list()).extracting(r->((Number)r.get("id")).longValue()).contains(3001L,1021L).doesNotContain(1030L,2001L,1010L,1004L);
    }

    @Test void explicitAssigneeIsStillProjectScopedAndGlobalReadersDoNotBecomeManagers() {
        WorkspaceTaskPage colleague=f.workspace.tasks("OPEN",null,null,3L,null,1,20,null);
        assertThat(colleague.total()).isEqualTo(1);
        f.login(5,"VIEWER");
        assertThat(tasks("OPEN").total()).isZero();
        assertThat(f.workspace.tasks("OPEN",null,null,2L,null,1,20,null).total()).isEqualTo(7);
        assertThat(f.workspace.management()).containsEntry("nodeTotal",0L).containsEntry("requestTotal",0L);
        f.login(6,"ADMIN");
        assertThat(f.workspace.tasks("OPEN",null,null,2L,null,1,20,null).total()).isEqualTo(7);
    }

    @Test void paginationFiltersAndLiteralKeywordSearchRemainCoherent() {
        WorkspaceTaskPage page=f.workspace.tasks("OPEN",null,null,null,null,2,2,null);
        assertThat(page.list()).extracting(r->((Number)r.get("id")).longValue()).containsExactly(1003L,4001L);
        assertThat(page.total()).isEqualTo(6);
        assertThat(f.workspace.tasks("OPEN","DOING",10L,null,null,1,20,null).total()).isEqualTo(2);
        assertThat(f.workspace.tasks("OPEN",null,null,null,"_",1,20,null).total()).isEqualTo(1);
        assertThat(f.workspace.tasks("OPEN",null,null,null,"%",1,20,null).total()).isZero();
        assertThat(f.workspace.tasks("OPEN",null,null,null,null,Integer.MAX_VALUE,100,null).list()).isEmpty();
    }

    @Test void requestedPrivateProjectAndInvalidFiltersFailWithBusinessErrors() {
        assertThatThrownBy(()->f.workspace.tasks("OPEN",null,20L,null,null,1,20,null))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(4003));
        for(String scope:List.of("unknown","ALL")) assertThatThrownBy(()->tasks(scope)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.tasks("OPEN","bad",null,null,null,1,20,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.tasks("OPEN",null,null,0L,null,1,20,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.tasks("OPEN",null,null,null,null,1,20,LocalDate.of(2026,10,27))).isInstanceOf(BusinessException.class);
    }

    @Test void malformedRelatedNodeCannotExposeAnotherProjectsName() {
        f.jdbc.update("UPDATE tasks SET related_node_id=201 WHERE id=1001");
        Map<String,Object> task=tasks("OPEN").list().get(0);
        assertThat(task.get("relatedNodeName")).isNull();
    }

    @Test void projectListFiltersBeforePaginationAndExposesTheCorrectProjectManager() {
        assertThat(f.projects.projects(1,1,"ACTIVE",null).total()).isEqualTo(2);
        assertThat(f.projects.projects(1,20,"ARCHIVED",null).total()).isEqualTo(1);
        assertThat(f.projects.projects(1,20).total()).isEqualTo(3);
        var selected=f.projects.projects(1,20,"ACTIVE","共享商城");
        assertThat(selected.total()).isEqualTo(1);
        assertThat(selected.list().get(0)).containsEntry("id",10L).containsEntry("pmId",1L).containsEntry("pmName","PM");
        assertThat(f.projects.projects(1,20,"ACTIVE","OLD").total()).isZero();
        assertThat(f.projects.projects(1,20,"ALL","不可见").total()).isZero();
        assertThatThrownBy(()->f.projects.projects(1,20,"bad",null)).isInstanceOf(BusinessException.class);
        f.login(5,"VIEWER");
        assertThat(f.projects.projects(1,20,"ACTIVE",null).total()).isEqualTo(3);
    }

    @Test void managementRequiresActualResponsibilityAndDoesNotTreatStaleOwnershipAsMembership() {
        assertThat(f.workspace.management()).containsEntry("nodeTotal",0L).containsEntry("requestTotal",0L);
        f.login(3,"USER");
        assertThat(f.workspace.management()).containsEntry("nodeTotal",1L).containsEntry("requestTotal",0L);
        f.login(1,"USER");
        Map<String,Object> result=f.workspace.management();
        assertThat(result).containsEntry("nodeTotal",2L).containsEntry("requestTotal",1L);
        @SuppressWarnings("unchecked") List<Map<String,Object>> nodes=(List<Map<String,Object>>)result.get("nodes");
        assertThat(nodes.get(0)).containsEntry("moduleOwnerId",3L).containsEntry("projectStatus","ACTIVE")
                .containsKeys("flags","progress");
    }

    @Test void workloadPeopleNamesAreLimitedToReadableProjectMembersWhileCountsStayAligned() {
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)f.reporting.workload(LocalDate.of(2026,10,27)).get("people");
        assertThat(rows).extracting(r->((Number)r.get("id")).longValue()).containsExactlyInAnyOrder(1L,2L,3L,4L);
        Map<String,Object> mine=rows.stream().filter(r->((Number)r.get("id")).longValue()==2L).findFirst().orElseThrow();
        assertThat(((Number)mine.get("openTaskCount")).longValue()).isEqualTo(tasks("WORKLOAD").total());
        assertThat(((Number)mine.get("weekDueCount")).longValue()).isEqualTo(6);
        f.login(5,"VIEWER");
        @SuppressWarnings("unchecked") List<Map<String,Object>> all=(List<Map<String,Object>>)f.reporting.workload(null).get("people");
        assertThat(all).extracting(r->((Number)r.get("id")).longValue()).contains(6L,7L);
    }

    @Test void managementLimitIncludesTotalAndWorkItemDrilldownUsesOriginalWorkloadScope() {
        f.login(1,"USER");
        for(int i=0;i<105;i++) f.node(500+i,10,100L,3,"IN_PROGRESS","2026-11-06",true,"额外风险"+i);
        Map<String,Object> result=f.workspace.management();
        assertThat(result).containsEntry("nodeTotal",107L);
        assertThat((List<?>)result.get("nodes")).hasSize(100);
        f.login(2,"USER");
        assertThat(f.workspace.workItems(3L,"ACTIVE",1,20).total()).isEqualTo(107);
        assertThat(f.workspace.workItems(2L,"OVERDUE",1,20).total()).isZero();
        f.login(6,"ADMIN");
        assertThat(f.workspace.workItems(2L,"OVERDUE",1,20).total()).isEqualTo(1);
    }
}
