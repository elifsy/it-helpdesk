package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 兼容早期没有钉钉部门关联字段的数据库，只添加可空字段和唯一索引。 */
public final class DingTalkSchemaMigration {
    private DingTalkSchemaMigration() { }

    public static void upgrade(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (!hasDepartmentId(jdbc)) {
            try {
                jdbc.execute("alter table sys_dept add column ding_dept_id varchar(64) default null");
            } catch (DataAccessException failure) {
                // 多实例同时启动时，另一个实例可能已经完成同一项升级。
                if (!hasDepartmentId(jdbc)) throw failure;
            }
        }
        if (!hasUniqueDepartmentId(jdbc)) {
            try {
                jdbc.execute("create unique index uk_dept_ding on sys_dept(ding_dept_id)");
            } catch (DataAccessException failure) {
                if (!hasUniqueDepartmentId(jdbc)) throw failure;
            }
        }
    }

    private static boolean hasDepartmentId(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "sys_dept", "ding_dept_id")) {
                return columns.next();
            }
        }));
    }

    private static boolean hasUniqueDepartmentId(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Map<String, List<String>> indexes = new HashMap<>();
            try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "sys_dept", true, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                    if (name != null && column != null) indexes.computeIfAbsent(name, key -> new ArrayList<>()).add(column);
                }
            }
            return indexes.values().stream().anyMatch(columns -> columns.size() == 1 && "ding_dept_id".equalsIgnoreCase(columns.get(0)));
        }));
    }
}
