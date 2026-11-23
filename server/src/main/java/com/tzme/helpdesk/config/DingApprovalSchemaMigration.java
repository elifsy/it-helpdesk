package com.tzme.helpdesk.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.Map;

/** 为已有资产申请添加可空的审批和交接字段。 */
public final class DingApprovalSchemaMigration {
    private DingApprovalSchemaMigration() { }
    public static void upgrade(DataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);
        for(var field:Map.of("ding_instance_id","varchar(64)","ding_status","varchar(24)",
                "ding_synced_at","datetime","ding_message","varchar(255)",
                "handover_status","varchar(16)","handover_by","varchar(64)","handover_at","datetime").entrySet()) {
            if(hasColumn(jdbc,field.getKey())) continue;
            try { jdbc.execute("alter table it_asset_request add column "+field.getKey()+" "+field.getValue()+" default null"); }
            catch(DataAccessException failure) { if(!hasColumn(jdbc,field.getKey())) throw failure; }
        }
    }
    private static boolean hasColumn(JdbcTemplate jdbc,String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"it_asset_request",name)) {
                return columns.next();
            }
        }));
    }
}
