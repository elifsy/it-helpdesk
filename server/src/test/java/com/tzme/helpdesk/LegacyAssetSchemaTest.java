package com.tzme.helpdesk;

import com.tzme.helpdesk.config.AssetSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LegacyAssetSchemaTest {
    @Test void addsOptionalFieldsAndCopiesLegacyNumberOnceWithoutChangingExistingAssetOrAllocation() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy-asset-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        db.execute("create table sys_setting(setting_key varchar(64) primary key,setting_value varchar(64))");
        db.update("insert into sys_setting values('management-lock','{}')");
        db.execute("create table it_asset(id bigint primary key,no varchar(32),name varchar(128),status varchar(32),user_name varchar(64),borrower varchar(64),updated_at datetime)");
        db.update("insert into it_asset values(1,'OLD-ASSET','原有设备','借出中',null,'测试借用人','2026-01-01 12:00:00')");
        var before=db.queryForMap("select * from it_asset");
        AssetSchemaMigration.upgrade(source); AssetSchemaMigration.upgrade(source);
        assertEquals(before,db.queryForMap("select id,no,name,status,user_name,borrower,updated_at from it_asset"));
        assertNull(db.queryForObject("select model from it_asset",String.class));
        assertEquals("OLD-ASSET",db.queryForObject("select erp_no from it_asset",String.class));
        db.update("update it_asset set model='已填写型号', erp_no=null where id=1");
        AssetSchemaMigration.upgrade(source);
        assertEquals("已填写型号",db.queryForObject("select model from it_asset",String.class));
        assertNull(db.queryForObject("select erp_no from it_asset",String.class));
    }
}
