package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

public final class NotificationSchemaMigration {
    private NotificationSchemaMigration() { }

    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        if (!hasRecipientColumn(jdbc)) {
            try {
                jdbc.execute("alter table it_notify add column recipient_id bigint default null");
            } catch (DataAccessException failure) {
                if (!hasRecipientColumn(jdbc)) throw failure;
            }
        }
        if (!hasRecipientIndex(jdbc)) {
            try {
                jdbc.execute("create index idx_nt_recipient on it_notify(recipient_id,is_read,id)");
            } catch (DataAccessException failure) {
                if (!hasRecipientIndex(jdbc)) throw failure;
            }
        }
    }

    private static boolean hasRecipientColumn(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "it_notify", "recipient_id")) {
                return columns.next();
            }
        }));
    }

    private static boolean hasRecipientIndex(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "it_notify", false, true)) {
                while (indexes.next()) {
                    if ("idx_nt_recipient".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                }
                return false;
            }
        }));
    }
}
