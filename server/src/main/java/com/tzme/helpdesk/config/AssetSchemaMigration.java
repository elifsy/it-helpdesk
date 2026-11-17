package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** 为已有台账增加选填型号，不修改现有设备资料。 */
public final class AssetSchemaMigration {
    private AssetSchemaMigration() { }
    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);
        if(hasModel(jdbc)) return;
        try { jdbc.execute("alter table it_asset add column model varchar(128) default null"); }
        catch(DataAccessException failure) { if(!hasModel(jdbc)) throw failure; }
    }
    private static boolean hasModel(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"it_asset","model")) {
                return columns.next();
            }
        }));
    }
}
