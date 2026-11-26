package com.tzme.helpdesk.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
@RequiredArgsConstructor
public class ManagementSchemaInitializer implements InitializingBean {
    private final DataSource dataSource;
    @Override public void afterPropertiesSet() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/management.sql"), new ClassPathResource("db/attachments.sql"),
                new ClassPathResource("db/purchase.sql"), new ClassPathResource("db/missing-reports.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
        DingTalkSchemaMigration.upgrade(dataSource);
        KnowledgeSchemaMigration.upgrade(dataSource);
        AssetSchemaMigration.upgrade(dataSource);
        DingApprovalSchemaMigration.upgrade(dataSource);
        NotificationSchemaMigration.upgrade(dataSource);
        ApplicantIdentityMigration.upgrade(dataSource);
        AccountBadgeSchemaMigration.upgrade(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        addColumn(jdbc, "it_missing_report", "business_type", "varchar(12) not null default 'REPAIR'");
        addColumn(jdbc, "it_missing_report", "asset_no", "varchar(32) default null");
        addColumn(jdbc, "it_missing_report", "new_asset_no", "varchar(32) default null");
        addColumn(jdbc, "it_missing_report", "expect_return", "date default null");
        addColumn(jdbc, "it_missing_report", "asset_request_no", "varchar(32) default null");
        Boolean hasCategoryNote = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "it_ticket", "category_note")) {
                return columns.next();
            }
        });
        if (!Boolean.TRUE.equals(hasCategoryNote))
            jdbc.execute("alter table it_ticket add column category_note varchar(200) default null");
        Boolean hasAssignedAt = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "it_ticket", "assigned_at")) {
                return columns.next();
            }
        });
        if (!Boolean.TRUE.equals(hasAssignedAt))
            jdbc.execute("alter table it_ticket add column assigned_at datetime default null");
        if (addTicketColumn(jdbc, "sla_reminded_at", "datetime")) {
            jdbc.update("update it_ticket t set sla_reminded_at=(select max(n.created_at) from it_notify n "
                    + "where n.type='SLA' and n.ref_no=t.no and n.recipient_id=t.assignee_id "
                    + "and n.title='工单已超时'),updated_at=updated_at "
                    + "where t.assignee_id is not null and t.status not in ('PENDING_CONFIRM','CLOSED','REJECTED','CANCELLED')");
        }
        addTicketColumn(jdbc, "confirmation_reminded_at", "datetime");
        addTicketColumn(jdbc, "sla_calendar_json", "text");
        addTicketColumn(jdbc, "response_due_at", "datetime");
        addTicketColumn(jdbc, "response_work_minutes", "bigint");
        addTicketColumn(jdbc, "resolve_work_minutes", "bigint");
        addTicketColumn(jdbc, "sla_paused_at", "datetime");
        addTicketColumn(jdbc, "sla_paused_seconds", "bigint");
        jdbc.update("update it_ticket set assigned_at=coalesce("
                + "(select max(created_at) from it_ticket_event where ticket_no=it_ticket.no and action='DISPATCH'),"
                + "accepted_at,created_at), updated_at=updated_at where assignee_id is not null and assigned_at is null");
        Long initialized=jdbc.queryForObject("select count(*) from sys_setting where setting_key='asset-categories-initialized'",Long.class);
        if(initialized==null || initialized==0) {
            var defaults=java.util.List.of("终端设备","打印复印","网络设备","服务器","会议室","软件许可");
            for(int index=0;index<defaults.size();index++) jdbc.update("insert ignore into it_asset_category(name,sort) values(?,?)",defaults.get(index),index);
        }
        jdbc.update("insert ignore into it_asset_category(name,sort) select distinct category,100 from it_asset where category is not null and trim(category)<>''");
        jdbc.update("insert ignore into sys_setting(setting_key,setting_value) values('asset-categories-initialized','true')");
        jdbc.queryForList("select id,role from sys_user").forEach(row -> {
            String before=String.valueOf(row.get("role"));
            String role=com.tzme.helpdesk.security.Roles.normalize(before);
            if(!role.equals(before)) {
                int changed=jdbc.update("update sys_user set role=? where id=? and role=?",role,row.get("id"),before);
                if(changed>0) jdbc.update("insert into sys_audit_log(actor_name,action,target,detail) values('系统升级','ROLE_MIGRATE',?,?)",
                        String.valueOf(row.get("id")),before+" → "+role);
            }
        });
        Boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()));
        if (!Boolean.TRUE.equals(mysql)) return;
        Long length = jdbc.queryForObject("select character_maximum_length from information_schema.columns "
                + "where table_schema=database() and table_name='sys_user' and column_name='role'", Long.class);
        String defaultRole=jdbc.queryForObject("select column_default from information_schema.columns "
                + "where table_schema=database() and table_name='sys_user' and column_name='role'",String.class);
        if (length != null && (length < 128 || !"USER".equals(defaultRole)))
            jdbc.execute("alter table sys_user modify role varchar(128) not null default 'USER'");
    }

    private boolean addTicketColumn(JdbcTemplate jdbc, String name, String type) {
        return addColumn(jdbc, "it_ticket", name, type + " default null");
    }

    private boolean addColumn(JdbcTemplate jdbc, String table, String name, String definition) {
        Boolean exists = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, name)) {
                return columns.next();
            }
        });
        if (Boolean.TRUE.equals(exists)) return false;
        jdbc.execute("alter table " + table + " add column " + name + " " + definition);
        return true;
    }
}
