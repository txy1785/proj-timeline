package com.projtimeline.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.data.DataAccess;
import com.projtimeline.notification.NotificationService;
import com.projtimeline.project.ProjectService;
import com.projtimeline.report.ReportingService;
import com.projtimeline.security.CurrentUser;
import com.projtimeline.security.PermissionService;
import com.projtimeline.work.NodeNoteService;
import com.projtimeline.work.WorkService;
import com.projtimeline.workspace.WorkspaceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/** A fresh in-memory relational database per test; never connects to a user database. */
public final class UxDatabaseFixture {
    public final JdbcTemplate jdbc;
    public final DataAccess db;
    public final Clock clock = Clock.fixed(Instant.parse("2026-10-26T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
    public final PermissionService permissions;
    public final ProjectService projects;
    public final WorkspaceService workspace;
    public final NotificationService notifications = mock(NotificationService.class);
    public final WorkService work;
    public final NodeNoteService nodeNotes;
    public final ReportingService reporting;

    public UxDatabaseFixture() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:ux_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        db = new DataAccess(new NamedParameterJdbcTemplate(source), mapper);
        permissions = new PermissionService(db);
        projects = new ProjectService(db, permissions, clock);
        workspace = new WorkspaceService(db, permissions, projects, clock);
        work = new WorkService(db, permissions, projects, notifications, mapper, clock);
        nodeNotes = new NodeNoteService(db, permissions, clock);
        reporting = new ReportingService(db, permissions, notifications, mapper, clock);
        for (String ddl : new String[]{
                "CREATE TABLE users(id BIGINT PRIMARY KEY,name VARCHAR(100),username VARCHAR(100),active BOOLEAN,global_role VARCHAR(20))",
                "CREATE TABLE projects(id BIGINT PRIMARY KEY,name VARCHAR(100),code VARCHAR(30),status VARCHAR(20),description VARCHAR(1000),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE project_members(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,user_id BIGINT,project_role VARCHAR(20))",
                "CREATE TABLE modules(id BIGINT PRIMARY KEY,project_id BIGINT,name VARCHAR(100),description VARCHAR(1000),owner_id BIGINT,sort_order INT DEFAULT 0)",
                "CREATE TABLE nodes(id BIGINT PRIMARY KEY,project_id BIGINT,module_id BIGINT,name VARCHAR(200),description VARCHAR(1000),type VARCHAR(20) DEFAULT 'FEATURE',status VARCHAR(20),owner_id BIGINT,plan_start DATE,plan_end DATE,blocked BOOLEAN DEFAULT FALSE,blocked_reason VARCHAR(1000),overdue_reason VARCHAR(1000),actual_start DATE,actual_end DATE,created_by BIGINT,sort_order INT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE tasks(id BIGINT AUTO_INCREMENT PRIMARY KEY,node_id BIGINT,name VARCHAR(200),description VARCHAR(1000),kind VARCHAR(20) DEFAULT 'FEATURE',related_node_id BIGINT,situation VARCHAR(2000),handling VARCHAR(2000),assignee_id BIGINT,status VARCHAR(20) DEFAULT 'TODO',due_date DATE,estimate_days DECIMAL(8,2),created_by BIGINT,done_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE task_notes(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,title VARCHAR(200),content MEDIUMTEXT,author_id BIGINT,updated_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP)",
                "CREATE TABLE node_notes(id BIGINT AUTO_INCREMENT PRIMARY KEY,node_id BIGINT,title VARCHAR(200),content MEDIUMTEXT,author_id BIGINT,updated_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP)",
                "CREATE TABLE task_work_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,days DECIMAL(8,2))",
                "CREATE TABLE node_deliverables(id BIGINT AUTO_INCREMENT PRIMARY KEY,node_id BIGINT,status VARCHAR(20),project_id BIGINT,name VARCHAR(200),owner_id BIGINT,remark VARCHAR(500),attachment_id BIGINT,sort_order INT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE node_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,node_id BIGINT,user_id BIGINT,action VARCHAR(40),detail VARCHAR(1000),before_value VARCHAR(4000),after_value VARCHAR(4000),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE module_change_requests(id BIGINT PRIMARY KEY,project_id BIGINT,module_id BIGINT,requested_by BIGINT,name VARCHAR(100),owner_id BIGINT,status VARCHAR(20),created_at TIMESTAMP)",
                "CREATE TABLE attachments(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,owner_type VARCHAR(20),owner_id BIGINT,category VARCHAR(40),title VARCHAR(200),original_name VARCHAR(200),content_type VARCHAR(100),size_bytes BIGINT,sha256 VARCHAR(64),version INT,remark VARCHAR(1000),uploader_id BIGINT,created_at TIMESTAMP,deleted BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE notifications(id BIGINT PRIMARY KEY,user_id BIGINT,read_at TIMESTAMP)"
        }) jdbc.execute(ddl);
        jdbc.execute("INSERT INTO users VALUES(1,'PM','pm',TRUE,'USER'),(2,'执行人','member',TRUE,'USER'),(3,'模块负责人','owner',TRUE,'USER'),(4,'外部项目经理','outside',TRUE,'USER'),(5,'只读','viewer',TRUE,'VIEWER'),(6,'管理员','admin',TRUE,'ADMIN'),(7,'完全无关人员','unrelated',TRUE,'USER')");
        jdbc.execute("INSERT INTO projects(id,name,code,status) VALUES(10,'共享商城','MALL','ACTIVE'),(20,'不可见项目','PRIVATE','ACTIVE'),(30,'归档项目','OLD','ARCHIVED'),(40,'另一共享项目','APP','ACTIVE')");
        jdbc.execute("INSERT INTO project_members(project_id,user_id,project_role) VALUES(10,1,'PM'),(10,2,'MEMBER'),(10,3,'MEMBER'),(20,4,'PM'),(20,7,'MEMBER'),(30,1,'PM'),(30,2,'MEMBER'),(30,3,'MEMBER'),(40,4,'PM'),(40,2,'MEMBER')");
        jdbc.execute("INSERT INTO modules(id,project_id,name,owner_id) VALUES(100,10,'订单',3),(200,20,'私有模块',4),(300,30,'归档模块',3),(400,40,'购物车',4)");
        node(101,10,100L,3,"IN_PROGRESS","2026-10-26",false,"订单接口开发");
        node(102,10,null,1,"IN_PROGRESS","2026-11-06",true,"缺陷修复");
        jdbc.execute("UPDATE nodes SET type='BUGFIX' WHERE id=102");
        node(103,10,100L,3,"DONE","2026-10-25",false,"已完成交付");
        node(104,10,100L,3,"CANCELLED","2026-10-25",false,"已取消交付");
        node(201,20,200L,2,"IN_PROGRESS","2026-10-26",false,"已失去成员资格的负责人");
        node(301,30,300L,3,"IN_PROGRESS","2026-10-26",false,"归档历史");
        node(401,40,400L,4,"IN_PROGRESS","2026-11-06",false,"购物车调整");
        task(1001,101,2,"DOING","2026-10-26","逾期任务");
        task(1002,101,2,"TODO","2026-10-27","今天任务");
        task(1003,101,2,"TODO","2026-10-29","后续任务");
        task(1004,101,2,"TODO",null,"without_date");
        task(1005,101,2,"DONE","2026-10-24","已完成任务");
        task(1006,101,3,"DOING","2026-10-26","他人的任务");
        task(1010,102,2,"DOING","2026-11-02","下周任务");
        task(1020,103,2,"DONE","2026-10-25","已关闭交付的完成任务");
        task(1021,103,2,"TODO","2026-10-26","关闭交付中的历史未完成任务");
        task(1030,104,2,"TODO","2026-10-26","取消任务");
        task(1031,104,2,"DONE","2026-10-24","取消交付中的完成历史");
        task(2001,201,2,"DOING","2026-10-26","不可见任务");
        task(3001,301,2,"DOING","2026-10-26","归档历史任务");
        task(4001,401,2,"TODO","2026-10-30","跨项目本人任务");
        jdbc.execute("INSERT INTO module_change_requests VALUES(1,10,100,2,'交易模块',3,'PENDING',TIMESTAMP '2026-10-27 10:00:00'),(2,20,200,4,'私有申请',4,'PENDING',TIMESTAMP '2026-10-27 09:00:00')");
        jdbc.execute("INSERT INTO notifications VALUES(1,2,NULL),(2,2,NULL),(3,2,TIMESTAMP '2026-10-27 09:00:00'),(4,1,NULL)");
        login(2,"USER");
    }

    public void node(long id,long project,Long module,long owner,String status,String end,boolean blocked,String name) {
        jdbc.update("INSERT INTO nodes(id,project_id,module_id,name,status,owner_id,plan_start,plan_end,blocked) VALUES(?,?,?,?,?,?,DATE '2026-10-19',?,?)",
                id,project,module,name,status,owner,java.sql.Date.valueOf(end),blocked);
    }
    public void task(long id,long node,long owner,String status,String due,String name) {
        jdbc.update("INSERT INTO tasks(id,node_id,name,assignee_id,status,due_date) VALUES(?,?,?,?,?,?)",id,node,name,owner,status,due==null?null:java.sql.Date.valueOf(due));
    }
    public void login(long id,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CurrentUser(id,"test","Test",role),null,List.of()));
    }
}
