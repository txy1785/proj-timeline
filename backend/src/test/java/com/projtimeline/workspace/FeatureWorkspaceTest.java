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

import static org.assertj.core.api.Assertions.*;

class FeatureWorkspaceTest {
    private UxDatabaseFixture f;
    @BeforeEach void setup(){f=new UxDatabaseFixture();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private WorkspaceDeliveryPage deliveries(String scope,Long owner){return f.workspace.deliveries(scope,null,null,owner,null,null,false,1,100);}
    private WorkspaceTaskPage bugs(String scope){return f.workspace.bugs(scope,null,null,null,null,false,1,100);}

    @Test void deliveriesUseOwnershipMembershipLifecycleAndServerClock(){
        f.login(3,"USER");
        assertThat(deliveries("OPEN",null).list()).extracting(row->row.get("id")).containsExactly(101L);
        assertThat(deliveries("OPEN",null).counts()).containsExactlyInAnyOrderEntriesOf(Map.of("open",1L,"doing",1L,"overdue",1L,"done",1L));
        assertThat(deliveries("DONE",null).list()).extracting(row->row.get("id")).containsExactly(103L);
        assertThat(deliveries("HISTORY",null).total()).isEqualTo(4);
        f.login(2,"USER");
        assertThat(deliveries("HISTORY",null).list()).isEmpty();
        assertThat(deliveries("OPEN",3L).list()).extracting(row->row.get("id")).doesNotContain(301L,201L);
    }

    @Test void deliveryFiltersAndPaginationStayServerSideAndLiteral(){
        f.login(3,"USER");f.node(502,10,100L,3,"NOT_STARTED","2026-10-28",false,"报告_%!");
        assertThat(f.workspace.deliveries("OPEN",null,null,null,"%",null,false,1,20).list()).extracting(row->row.get("id")).containsExactly(502L);
        assertThat(f.workspace.deliveries("OPEN",null,null,null,null,LocalDate.of(2026,10,28),false,2,1).total()).isEqualTo(2);
        assertThat(f.workspace.deliveries("OPEN",null,null,null,null,null,true,1,20).list()).extracting(row->row.get("id")).containsExactly(101L);
        assertThat(f.workspace.deliveries("OPEN","NOT_STARTED",null,null,null,null,false,1,20).total()).isEqualTo(1);
        assertThat(f.workspace.deliveries("OPEN",null,null,null,null,null,false,Integer.MAX_VALUE,100).list()).isEmpty();
    }

    @Test void bugScopeIncludesDeliveredOriginsButExcludesCancelledArchivedAndPrivate(){
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=103 WHERE id=1001");
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=104 WHERE id=1002");
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=201 WHERE id=1003");
        f.jdbc.update("UPDATE tasks SET kind='BUG' WHERE id IN(3001,2001)");
        WorkspaceTaskPage open=bugs("OPEN");
        assertThat(open.list()).extracting(row->row.get("id")).containsExactly(1001L,1003L);
        assertThat(open.list().get(0)).containsEntry("originNodeId",103L).containsEntry("originNodeStatus","DONE").containsEntry("nodeName","已完成交付").containsEntry("nodeId",101L);
        assertThat(open.list().get(1)).containsEntry("originNodeId",101L).containsEntry("relatedNodeName",null);
        assertThat(open.counts()).containsEntry("open",2L).containsEntry("overdue",1L);
        assertThat(bugs("HISTORY").list()).extracting(row->row.get("id")).contains(1002L,3001L).doesNotContain(2001L);
    }

    @Test void bugAssigneeStatusSearchAndOverdueFiltersCannotEscapeMembership(){
        f.jdbc.update("UPDATE tasks SET kind='BUG' WHERE id IN(1001,1003,1006,2001)");
        assertThat(f.workspace.bugs("OPEN","DOING",null,null,null,false,1,20).total()).isEqualTo(1);
        assertThat(f.workspace.bugs("OPEN",null,null,null,null,true,1,20).total()).isEqualTo(1);
        assertThat(f.workspace.bugs("OPEN",null,null,3L,null,false,1,20).list()).extracting(row->row.get("id")).containsExactly(1006L);
        assertThat(f.workspace.bugs("OPEN",null,null,null,"不可见",false,1,20).total()).isZero();
        assertThatThrownBy(()->f.workspace.bugs("OPEN",null,20L,null,null,false,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.deliveries("OPEN",null,20L,null,null,null,false,1,20)).isInstanceOf(BusinessException.class);
    }

    @Test void newWorkloadCountsEqualTheirDrilldownsIncludingBugsOnDeliveredFeatures(){
        f.jdbc.update("UPDATE tasks SET kind='BUG',related_node_id=103 WHERE id=1001");
        @SuppressWarnings("unchecked") List<Map<String,Object>> people=(List<Map<String,Object>>)f.reporting.workload(LocalDate.of(2026,10,27)).get("people");
        for(Map<String,Object> person:people){
            long id=((Number)person.get("id")).longValue();
            assertThat(((Number)person.get("deliveryOpenCount")).longValue()).isEqualTo(deliveries("OPEN",id).total());
            assertThat(((Number)person.get("deliveryDoingCount")).longValue()).isEqualTo(f.workspace.deliveries("OPEN","IN_PROGRESS",null,id,null,null,false,1,100).total());
            assertThat(((Number)person.get("deliveryOverdueCount")).longValue()).isEqualTo(f.workspace.deliveries("OPEN",null,null,id,null,null,true,1,100).total());
            assertThat(((Number)person.get("deliveryWeekDueCount")).longValue()).isEqualTo(f.workspace.deliveries("OPEN",null,null,id,null,LocalDate.of(2026,10,27),false,1,100).total());
            assertThat(((Number)person.get("openBugCount")).longValue()).isEqualTo(f.workspace.bugs("OPEN",null,null,id,null,false,1,100).total());
            assertThat(((Number)person.get("overdueBugCount")).longValue()).isEqualTo(f.workspace.bugs("OPEN",null,null,id,null,true,1,100).total());
        }
    }

    @Test void globalReadRolesCanReadButDoNotGainMutationRightsAndInvalidInputsFail(){
        f.login(5,"VIEWER");assertThat(deliveries("OPEN",2L).list()).extracting(row->row.get("id")).contains(201L);
        assertThatThrownBy(()->f.workspace.deliveries("bad",null,null,null,null,null,false,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.bugs("OPEN","bad",null,null,null,false,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.bugs("OPEN",null,null,0L,null,false,1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.workspace.deliveries("OPEN",null,null,null,"x".repeat(201),null,false,1,20)).isInstanceOf(BusinessException.class);
    }

    @Test void legacyOnlyFiltersBeforePaginationCountsAndTotalWithoutChangingOldDefault(){
        f.jdbc.update("UPDATE tasks SET kind='BUG' WHERE id IN(1001,1002,1005)");
        WorkspaceTaskPage legacy=f.workspace.tasks("HISTORY",null,null,null,null,1,2,null,true);
        assertThat(legacy.total()).isEqualTo(9);
        assertThat(legacy.list()).extracting(row->row.get("kind")).doesNotContain("BUG");
        assertThat(legacy.counts()).containsEntry("open",4L).containsEntry("done",1L);
        assertThat(f.workspace.tasks("HISTORY",null,null,null,null,1,20,null).total()).isEqualTo(12);
    }
}
