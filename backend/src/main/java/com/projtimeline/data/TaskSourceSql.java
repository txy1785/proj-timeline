package com.projtimeline.data;

/** One same-project source rule for legacy routed Bugs, reads and write authorization. Aliases: t,n,p. */
public final class TaskSourceSql {
    private TaskSourceSql() {}
    public static final String ID = "CASE WHEN t.kind='BUG' AND related.id IS NOT NULL THEN related.id ELSE n.id END";
    public static final String STATUS = "CASE WHEN t.kind='BUG' AND related.id IS NOT NULL THEN related.status ELSE n.status END";
    public static final String NAME = "CASE WHEN t.kind='BUG' AND related.id IS NOT NULL THEN related.name ELSE n.name END";
    public static final String OWNER = "CASE WHEN t.kind='BUG' AND related.id IS NOT NULL THEN related.owner_id ELSE n.owner_id END";
    public static final String MODULE = "CASE WHEN t.kind='BUG' AND related.id IS NOT NULL THEN related.module_id ELSE n.module_id END";
    public static final String JOINS = " LEFT JOIN nodes related ON related.id=t.related_node_id AND related.project_id=n.project_id"
            + " LEFT JOIN modules source_module ON source_module.id=(" + MODULE + ") AND source_module.project_id=n.project_id ";
    public static final String FROM = " FROM tasks t JOIN nodes n ON n.id=t.node_id JOIN projects p ON p.id=n.project_id " + JOINS;
    public static final String OPEN_BUG = "t.kind='BUG' AND p.status='ACTIVE' AND ("+STATUS+")<>'CANCELLED' AND t.status IN('TODO','DOING')";
    public static final String DONE_BUG = "t.kind='BUG' AND p.status='ACTIVE' AND ("+STATUS+")<>'CANCELLED' AND t.status='DONE'";
    public static final String COLUMNS = ID + " origin_node_id," + STATUS + " origin_node_status," + NAME
            + " origin_node_name," + OWNER + " origin_owner_id,source_module.owner_id origin_module_owner_id";
}
