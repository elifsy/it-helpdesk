package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;

/** 只增加来源关联及内容版本；保留已有文章和统计。 */
public final class KnowledgeSchemaMigration {
    private KnowledgeSchemaMigration() { }
    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        addColumn(jdbc, "source_ticket_no", "varchar(32) default null");
        addColumn(jdbc, "revision", "int not null default 0");
        if (!hasSourceIndex(jdbc)) {
            try { jdbc.execute("create unique index uk_kb_source_ticket on it_kb_article(source_ticket_no)"); }
            catch (DataAccessException failure) { if (!hasSourceIndex(jdbc)) throw failure; }
        }
    }
    private static void addColumn(JdbcTemplate jdbc, String name, String definition) {
        if (hasColumn(jdbc, name)) return;
        try { jdbc.execute("alter table it_kb_article add column " + name + " " + definition); }
        catch (DataAccessException failure) { if (!hasColumn(jdbc, name)) throw failure; }
    }
    private static boolean hasColumn(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), null, "it_kb_article", name)) {
                return rows.next();
            }
        }));
    }
    private static boolean hasSourceIndex(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Map<String,List<String>> indexes = new HashMap<>();
            try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "it_kb_article", true, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                    if (name != null && column != null) indexes.computeIfAbsent(name, key -> new ArrayList<>()).add(column);
                }
            }
            return indexes.values().stream().anyMatch(columns -> columns.size() == 1 && "source_ticket_no".equalsIgnoreCase(columns.get(0)));
        }));
    }
}
