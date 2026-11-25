package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/** 为已有台账添加设备资料字段，并保留原有资产编号关联。 */
public final class AssetSchemaMigration {
    private static final String ERP_MIGRATION = "asset-erp-number-migrated";
    private static final String HOLDERS_MIGRATION = "asset-holder-identities-migrated";
    private AssetSchemaMigration() { }
    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);
        addColumn(jdbc,"model","varchar(128)");
        addColumn(jdbc,"icon","varchar(32)");
        addColumn(jdbc,"erp_no","varchar(32)"," unique");
        addColumn(jdbc,"user_id","bigint");
        addColumn(jdbc,"borrower_id","bigint");
        addColumn(jdbc,"borrow_soon_reminded_at","datetime");
        addColumn(jdbc,"borrow_overdue_reminded_at","datetime");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.queryForObject("select setting_value from sys_setting where setting_key='management-lock' for update", String.class);
            if (jdbc.queryForObject("select count(*) from sys_setting where setting_key=?", Integer.class, ERP_MIGRATION) == 0) {
                jdbc.update("update it_asset set erp_no=no,updated_at=updated_at where erp_no is null");
                jdbc.update("insert into sys_setting(setting_key,setting_value) values(?,'true')", ERP_MIGRATION);
            }
            if (jdbc.queryForObject("select count(*) from sys_setting where setting_key=?", Integer.class, HOLDERS_MIGRATION) == 0) {
                backfillHolder(jdbc, "user_name", "user_id");
                backfillHolder(jdbc, "borrower", "borrower_id");
                jdbc.update("insert into sys_setting(setting_key,setting_value) values(?,'true')", HOLDERS_MIGRATION);
            }
        });
    }

    private static void backfillHolder(JdbcTemplate jdbc, String name, String id) {
        jdbc.update("update it_asset a set " + id + "=(select case when count(*)=1 then min(u.id) end from sys_user u where u.name=trim(a."
                + name + ")),updated_at=updated_at where " + id + " is null and nullif(trim(" + name + "),'') is not null");
    }
    private static void addColumn(JdbcTemplate jdbc,String name,String type) {
        addColumn(jdbc,name,type,"");
    }
    private static void addColumn(JdbcTemplate jdbc,String name,String type,String constraint) {
        if(hasColumn(jdbc,name)) return;
        try { jdbc.execute("alter table it_asset add column "+name+" "+type+" default null"+constraint); }
        catch(DataAccessException failure) { if(!hasColumn(jdbc,name)) throw failure; }
    }
    private static boolean hasColumn(JdbcTemplate jdbc,String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"it_asset",name)) {
                return columns.next();
            }
        }));
    }
}
