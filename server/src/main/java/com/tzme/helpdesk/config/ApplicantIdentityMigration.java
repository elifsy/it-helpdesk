package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;

public final class ApplicantIdentityMigration {
    private ApplicantIdentityMigration() { }

    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        for (String table : List.of("it_asset_request", "it_purchase_request")) {
            if (hasColumn(jdbc, table)) continue;
            try {
                jdbc.execute("alter table " + table + " add column applicant_id bigint default null");
            } catch (DataAccessException error) {
                if (!hasColumn(jdbc, table)) throw error;
            }
        }
    }

    private static boolean hasColumn(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "applicant_id")) {
                return columns.next();
            }
        }));
    }
}
