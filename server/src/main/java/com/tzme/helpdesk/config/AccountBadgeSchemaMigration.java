package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

final class AccountBadgeSchemaMigration {
    private AccountBadgeSchemaMigration() { }

    static void upgrade(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        addColumn(jdbc, "sys_user", "badge_avatar", "varchar(16)");
        addColumn(jdbc, "it_ticket_event", "operator_id", "bigint");
    }

    private static void addColumn(JdbcTemplate jdbc, String table, String name, String type) {
        if (hasColumn(jdbc, table, name)) return;
        try {
            jdbc.execute("alter table " + table + " add column " + name + " " + type + " default null");
        } catch (DataAccessException failure) {
            if (!hasColumn(jdbc, table, name)) throw failure;
        }
    }

    private static boolean hasColumn(JdbcTemplate jdbc, String table, String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, name)) {
                return columns.next();
            }
        }));
    }
}
