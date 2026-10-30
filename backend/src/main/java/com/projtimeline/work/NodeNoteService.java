package com.projtimeline.work;

import com.projtimeline.common.BusinessException;
import com.projtimeline.data.DataAccess;
import com.projtimeline.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.projtimeline.common.RequestValues.text;
import static com.projtimeline.security.PermissionService.number;

/** 工作项进展记录：与任务记录同形态的命名 Markdown 文件，写入权限与工作项级附件一致。 */
@Service
public class NodeNoteService {
    private static final String NOTE_SELECT = """
            SELECT n.id,n.node_id,n.title,n.author_id,u.name author_name,n.content,n.created_at,
                   n.updated_by,n.updated_at,e.name updated_by_name
            FROM node_notes n
            JOIN users u ON u.id=n.author_id
            LEFT JOIN users e ON e.id=n.updated_by
            """;

    private final DataAccess db;
    private final PermissionService permissions;
    private final Clock clock;

    public NodeNoteService(DataAccess db, PermissionService permissions, Clock clock) {
        this.db = db; this.permissions = permissions; this.clock = clock;
    }

    public List<Map<String, Object>> list(long nodeId) {
        permissions.requireProjectRead(permissions.projectForNode(nodeId));
        return db.list(NOTE_SELECT + " WHERE n.node_id=:node ORDER BY n.created_at DESC, n.id DESC", Map.of("node", nodeId));
    }

    /** 已交付/取消的工作项仍可补记录（复盘、说明），归档项目与 VIEWER 由 requireNodeManager 拒绝。 */
    @Transactional
    public Map<String, Object> add(long nodeId, Map<String, Object> body) {
        permissions.requireNodeManager(nodeId);
        String title = WorkService.noteTitle(body);
        String content = WorkService.noteContent(body);
        lockNode(nodeId);
        long id = db.insert("INSERT INTO node_notes(node_id,title,author_id,content,created_at) VALUES(:node,:title,:author,:content,:created)",
                Map.of("node", nodeId, "title", title, "author", permissions.current().id(), "content", content,
                        "created", LocalDateTime.now(clock)));
        return one(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> note = requireEditable(id);
        String title = text(body, "title") == null ? Objects.toString(note.get("title"), "进展记录.md") : WorkService.noteTitle(body);
        String content = WorkService.noteContent(body);
        lockNode(number(note.get("nodeId")));
        if (db.update("UPDATE node_notes SET title=:title,content=:content,updated_by=:editor,updated_at=:updated WHERE id=:id",
                Map.of("id", id, "title", title, "content", content, "editor", permissions.current().id(),
                        "updated", LocalDateTime.now(clock))) != 1)
            throw BusinessException.notFound("记录不存在");
        return one(id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> note = requireEditable(id);
        lockNode(number(note.get("nodeId")));
        if (db.update("DELETE FROM node_notes WHERE id=:id", Map.of("id", id)) != 1) throw BusinessException.notFound("记录不存在");
    }

    /** 作者本人或工作项管理者可改删；作者身份不越过项目归档与 VIEWER 只读。 */
    private Map<String, Object> requireEditable(long id) {
        Map<String, Object> note = db.one("SELECT id,node_id,author_id,title FROM node_notes WHERE id=:id", Map.of("id", id), "记录不存在");
        long nodeId = number(note.get("nodeId"));
        permissions.requireProjectActive(permissions.projectForNode(nodeId));
        if (number(note.get("authorId")) != permissions.current().id()) permissions.requireNodeManager(nodeId);
        return note;
    }

    private Map<String, Object> one(long id) {
        return db.one(NOTE_SELECT + " WHERE n.id=:id", Map.of("id", id), "记录不存在");
    }

    private void lockNode(long nodeId) {
        db.one("SELECT id FROM nodes WHERE id=:id FOR UPDATE", Map.of("id", nodeId), "工作项不存在");
    }
}
