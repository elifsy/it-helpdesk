package com.tzme.helpdesk;

import com.tzme.helpdesk.config.DingTalkSchemaMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDingTalkSchemaTest {
    DriverManagerDataSource source;
    JdbcTemplate db;

    @BeforeEach void setup() {
        source = new DriverManagerDataSource("jdbc:h2:mem:legacy-dingtalk-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("create table sys_dept(id bigint auto_increment primary key,name varchar(64) not null,"
                + "parent_id bigint not null default 0,sort int not null default 0,created_at datetime default current_timestamp)");
        db.update("insert into sys_dept(id,name,parent_id,sort,created_at) values(1,'原有部门',0,7,'2024-01-01 09:00:00')");
    }

    @Test void upgradesLegacyTableAndPreservesAllExistingDepartmentData() {
        var before = db.queryForMap("select * from sys_dept where id=1");
        DingTalkSchemaMigration.upgrade(source);
        assertEquals(before, db.queryForMap("select id,name,parent_id,sort,created_at from sys_dept where id=1"));
        assertNull(db.queryForObject("select ding_dept_id from sys_dept where id=1", String.class));
        assertEquals(0, db.queryForList("select * from sys_dept where ding_dept_id='9001'").size());
    }

    @Test void repeatedUpgradePreservesBoundIdsAndEnforcesSingleDepartmentMapping() {
        DingTalkSchemaMigration.upgrade(source);
        db.update("update sys_dept set ding_dept_id='9001' where id=1");
        var before = db.queryForMap("select * from sys_dept where id=1");
        DingTalkSchemaMigration.upgrade(source);
        DingTalkSchemaMigration.upgrade(source);
        assertEquals(before, db.queryForMap("select * from sys_dept where id=1"));
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update("insert into sys_dept(name,ding_dept_id) values('重复关联','9001')"));
        db.update("insert into sys_dept(name) values('未关联部门甲'),('未关联部门乙')");
        assertEquals(3, db.queryForObject("select count(*) from sys_dept", Integer.class));
    }

    @Test void columnWithoutIndexGetsUniqueIndexWithoutLosingBindings() {
        db.execute("alter table sys_dept add column ding_dept_id varchar(64) default null");
        db.execute("create index legacy_dept_lookup on sys_dept(ding_dept_id)");
        db.update("update sys_dept set ding_dept_id='9001' where id=1");
        DingTalkSchemaMigration.upgrade(source);
        assertEquals("9001", db.queryForObject("select ding_dept_id from sys_dept where id=1", String.class));
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update("insert into sys_dept(name,ding_dept_id) values('重复关联','9001')"));
    }

    @Test void existingUniqueIndexWithDifferentNameIsAccepted() {
        db.execute("alter table sys_dept add column ding_dept_id varchar(64) default null");
        db.execute("create unique index existing_dept_ding on sys_dept(ding_dept_id)");
        DingTalkSchemaMigration.upgrade(source);
        db.update("update sys_dept set ding_dept_id='9001' where id=1");
        DingTalkSchemaMigration.upgrade(source);
        assertEquals("9001", db.queryForObject("select ding_dept_id from sys_dept where id=1", String.class));
        assertEquals(0, db.queryForObject("select count(*) from information_schema.indexes where table_name='sys_dept' and index_name='uk_dept_ding'", Integer.class));
    }
}
