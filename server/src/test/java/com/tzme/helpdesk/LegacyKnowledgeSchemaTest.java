package com.tzme.helpdesk;

import com.tzme.helpdesk.config.KnowledgeSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LegacyKnowledgeSchemaTest {
    @Test void upgradesLegacyKnowledgeWithoutChangingArticlesOrStatisticsAndIsRepeatable() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy-kb-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        db.execute("create table it_kb_article(id bigint auto_increment primary key,title varchar(200),views int,helpful int)");
        db.update("insert into it_kb_article(title,views,helpful) values('已有文章',123,45)");
        var previous=db.queryForMap("select * from it_kb_article");
        KnowledgeSchemaMigration.upgrade(source); KnowledgeSchemaMigration.upgrade(source);
        assertEquals(previous,db.queryForMap("select id,title,views,helpful from it_kb_article"));
        assertEquals(0,db.queryForObject("select revision from it_kb_article",Integer.class));
        db.update("insert into it_kb_article(title) values('另一个无来源文章')");
        db.update("update it_kb_article set source_ticket_no='IT-HISTORICAL',revision=3 where id=1");
        KnowledgeSchemaMigration.upgrade(source);
        assertEquals(3,db.queryForObject("select revision from it_kb_article where id=1",Integer.class));
        assertThrows(DataIntegrityViolationException.class,() -> db.update("insert into it_kb_article(title,source_ticket_no) values('重复来源','IT-HISTORICAL')"));
    }
}
