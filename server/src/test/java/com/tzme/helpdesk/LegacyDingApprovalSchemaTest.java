package com.tzme.helpdesk;

import com.tzme.helpdesk.config.DingApprovalSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LegacyDingApprovalSchemaTest {
    @Test void upgradesOldRequestsWithoutChangingExistingResultsAndCanRunAgain() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy-ding-approval-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        db.execute("create table it_asset_request(id bigint primary key,no varchar(32),status varchar(32),remark varchar(255),ding_status varchar(24))");
        db.update("insert into it_asset_request values(1,'AS-OLD','COMPLETED','原有审批结果',null)");
        var previous=db.queryForMap("select * from it_asset_request");
        DingApprovalSchemaMigration.upgrade(source); DingApprovalSchemaMigration.upgrade(source);
        assertEquals(previous,db.queryForMap("select id,no,status,remark,ding_status from it_asset_request"));
        for(String field:java.util.List.of("ding_instance_id","ding_status","ding_synced_at","ding_message"))
            assertNull(db.queryForObject("select "+field+" from it_asset_request where id=1",Object.class));
        db.update("update it_asset_request set ding_instance_id='BOUND-INSTANCE',ding_status='COMPLETED',ding_message='已同步' where id=1");
        DingApprovalSchemaMigration.upgrade(source);
        assertEquals("BOUND-INSTANCE",db.queryForObject("select ding_instance_id from it_asset_request where id=1",String.class));
        assertEquals("已同步",db.queryForObject("select ding_message from it_asset_request where id=1",String.class));
    }
}
