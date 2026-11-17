package com.tzme.helpdesk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzme.helpdesk.job.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:knowledge-regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts="/db/fixture.sql", config=@SqlConfig(encoding="UTF-8"))
class KnowledgeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean SlaScanJob slaJob;
    @MockBean ContactSyncJob contactJob;
    String login(String name) throws Exception {
        return body(mvc.perform(post("/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("demoUser",name)))))
                .path("token").asText();
    }
    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode finish(String actor,String note) throws Exception {
        return body(mvc.perform(post("/tickets/IT-TEST-0001/finish").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("code","RESTART","note",note))).header("Authorization","Bearer "+actor)));
    }
    JsonNode confirm(String actor,boolean solved) throws Exception {
        return body(mvc.perform(post("/tickets/IT-TEST-0001/confirm").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("score",5,"solved",solved))).header("Authorization","Bearer "+actor)));
    }
    ResultActions captureRequest(String actor) throws Exception {
        return mvc.perform(post("/kb/manage/from-ticket").contentType("application/json")
                .content("{\"ticketNo\":\"IT-TEST-0001\"}").header("Authorization","Bearer "+actor));
    }
    JsonNode capture(String actor) throws Exception { return body(captureRequest(actor)); }
    ResultActions action(String actor,long id,String action,int revision) throws Exception {
        return mvc.perform(post("/kb/manage/"+id+"/"+action).contentType("application/json")
                .content("{\"revision\":"+revision+"}").header("Authorization","Bearer "+actor));
    }
    ResultActions save(String actor,long id,int revision) throws Exception {
        return mvc.perform(put("/kb/manage/"+id).contentType("application/json").content(json.writeValueAsBytes(Map.of("revision",revision,
                "title","  电脑驱动异常处理步骤  ","category","终端","summary","重新安装驱动并检查网络","content","一、检查设备管理器。\n二、重新安装驱动。\n三、重启并验证。")))
                .header("Authorization","Bearer "+actor));
    }

    @Test void createsOneDraftOnlyAfterConfirmedClosureUsingLatestConclusion() throws Exception {
        String owner=login("张伟"),it=login("李强");
        db.update("update it_ticket set location='A座3楼财务室',asset_no='ASSET-TEST-1' where no='IT-TEST-0001'");
        assertEquals("PENDING_CONFIRM",finish(it,"临时重启恢复，待确认").path("status").asText());
        assertEquals(0,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        assertEquals("IN_PROGRESS",confirm(owner,false).path("status").asText());
        assertEquals(0,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        finish(it,"  排查到驱动异常，重装驱动并重启后恢复正常  ");
        assertEquals("CLOSED",confirm(owner,true).path("status").asText());
        JsonNode draft=capture(it);
        assertEquals("DRAFT",draft.path("status").asText());
        assertEquals("IT-TEST-0001",draft.path("sourceTicketNo").asText());
        assertEquals("测试工单",draft.path("title").asText()); assertEquals("终端",draft.path("category").asText());
        assertEquals("排查到驱动异常，重装驱动并重启后恢复正常",draft.path("summary").asText());
        assertTrue(draft.path("content").asText().contains("回归测试专用工单"));
        for(String privateField:List.of("临时重启","张伟","李强","A座3楼财务室","ASSET-TEST-1","IT-TEST-0001"))
            assertFalse(draft.path("content").asText().contains(privateField));
        for(int i=0;i<3;i++) assertEquals(draft.path("id"),capture(it).path("id"));
        mvc.perform(post("/tickets/IT-TEST-0001/confirm").contentType("application/json").content("{\"score\":5,\"solved\":true}")
                .header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());
        assertEquals(1,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from sys_audit_log where action='KB_CAPTURE'",Integer.class));
    }

    @Test void draftsAreHiddenFromPublicDetailVotesSearchAndRecommendations() throws Exception {
        String owner=login("张伟"),it=login("李强");
        finish(it,"重新安装驱动并重启，检查网络连接"); confirm(owner,true);
        long id=capture(it).path("id").asLong();
        for(String actor:List.of(owner,login("赵敏"),login("陈静"))) {
            mvc.perform(get("/kb/"+id).header("Authorization","Bearer "+actor)).andExpect(status().isNotFound());
            mvc.perform(post("/kb/"+id+"/vote").contentType("application/json").content("{\"helpful\":true}")
                    .header("Authorization","Bearer "+actor)).andExpect(status().isNotFound());
            assertEquals(0,body(mvc.perform(get("/kb").header("Authorization","Bearer "+actor))).size());
            assertEquals(0,body(mvc.perform(get("/kb/recommend").param("cat1","终端").param("desc","驱动异常")
                    .header("Authorization","Bearer "+actor))).size());
        }
        assertEquals(0,db.queryForObject("select views+helpful+not_helpful from it_kb_article where id=?",Integer.class,id));
    }

    @Test void editPublishWithdrawArchiveAndRestoreProtectRevisionsAndFeedback() throws Exception {
        String owner=login("张伟"),it=login("李强"),admin=login("陈静");
        finish(it,"排查驱动并重装，确认恢复"); confirm(owner,true);
        long id=capture(it).path("id").asLong();
        JsonNode saved=body(save(it,id,0));
        assertEquals(1,saved.path("revision").asInt()); assertEquals("电脑驱动异常处理步骤",saved.path("title").asText());
        action(it,id,"publish",1).andExpect(status().isForbidden());
        save(admin,id,0).andExpect(status().isConflict());
        action(admin,id,"publish",0).andExpect(status().isConflict());
        assertEquals("PUBLISHED",body(action(admin,id,"publish",1)).path("status").asText());
        assertEquals(1,body(mvc.perform(get("/kb").param("keyword","驱动").header("Authorization","Bearer "+owner))).size());
        assertEquals(1,body(mvc.perform(get("/kb/recommend").param("cat1","终端").param("desc","驱动异常")
                .header("Authorization","Bearer "+owner))).size());
        JsonNode detail=body(mvc.perform(get("/kb/"+id).header("Authorization","Bearer "+owner)));
        assertEquals(saved.path("content"),detail.path("content")); assertFalse(detail.hasNonNull("sourceTicketNo"));
        body(mvc.perform(post("/kb/"+id+"/vote").contentType("application/json").content("{\"helpful\":true}").header("Authorization","Bearer "+owner)));
        JsonNode withdrawn=body(action(admin,id,"withdraw",2));
        assertEquals("DRAFT",withdrawn.path("status").asText());
        assertEquals(1,withdrawn.path("views").asInt()); assertEquals(1,withdrawn.path("helpful").asInt());
        assertEquals(0,body(mvc.perform(get("/kb").header("Authorization","Bearer "+owner))).size());
        body(save(it,id,3)); assertEquals("ARCHIVED",body(action(it,id,"archive",4)).path("status").asText());
        assertEquals("ARCHIVED",capture(it).path("status").asText());
        assertEquals(1,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        body(save(it,id,5)); body(action(admin,id,"publish",6));
        // 修改已发布知识会转为草稿，统计保留，并且必须重新发布。
        assertEquals("DRAFT",body(save(it,id,7)).path("status").asText());
        mvc.perform(get("/kb/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isNotFound());
        assertEquals(2,db.queryForObject("select views+helpful from it_kb_article where id=?",Integer.class,id));
    }

    @Test void managementEnforcesPermissionsAndOnlyCapturesClosedTicketsWithConclusions() throws Exception {
        String it=login("李强"),admin=login("陈静");
        for(String name:List.of("张伟","赵敏")) {
            String actor=login(name);
            mvc.perform(get("/kb/manage").header("Authorization","Bearer "+actor)).andExpect(status().isForbidden());
            mvc.perform(get("/kb/manage/1").header("Authorization","Bearer "+actor)).andExpect(status().isForbidden());
            captureRequest(actor).andExpect(status().isForbidden()); save(actor,1,0).andExpect(status().isForbidden());
            action(actor,1,"publish",0).andExpect(status().isForbidden());
        }
        for(String state:List.of("NEW","IN_PROGRESS","PENDING_CONFIRM","REJECTED","CANCELLED")) {
            db.update("update it_ticket set status=?,solution_note='可沉淀结论' where no='IT-TEST-0001'",state);
            captureRequest(it).andExpect(status().isBadRequest());
        }
        db.update("update it_ticket set status='CLOSED',solution_note='  ' where no='IT-TEST-0001'");
        captureRequest(admin).andExpect(status().isBadRequest());
        assertEquals(0,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        mvc.perform(get("/kb/manage").param("status","INVALID").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/kb/manage/from-ticket").contentType("application/json").content("{\"ticketNo\":\"MISSING\"}")
                .header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
    }

    @Test void concurrentHistoricalCaptureCreatesOnceAndNeverChangesTheTicket() throws Exception {
        db.update("update it_ticket set status='CLOSED',solution_note='更新驱动并验证恢复',closed_at=now() where no='IT-TEST-0001'");
        var previous=db.queryForMap("select * from it_ticket where no='IT-TEST-0001'");
        String it=login("李强"); var executor=Executors.newFixedThreadPool(2);
        try {
            var results=executor.invokeAll(List.<Callable<JsonNode>>of(() -> capture(it),() -> capture(it)));
            assertEquals(results.get(0).get(15,TimeUnit.SECONDS).path("id"),results.get(1).get(15,TimeUnit.SECONDS).path("id"));
        } finally { executor.shutdownNow(); }
        assertEquals(1,db.queryForObject("select count(*) from it_kb_article",Integer.class));
        assertEquals(previous,db.queryForMap("select * from it_ticket where no='IT-TEST-0001'"));
    }

    @Test void paginationSearchAndBlankValidationApplyToDrafts() throws Exception {
        String it=login("李强");
        for(int i=0;i<23;i++) db.update("insert into it_kb_article(title,category,summary,content,status) values(?,'终端','排查摘要','处理步骤','DRAFT')","分页知识"+i);
        JsonNode second=body(mvc.perform(get("/kb/manage").param("page","2").param("size","20").header("Authorization","Bearer "+it)));
        assertEquals(23,second.path("total").asInt()); assertEquals(3,second.path("list").size());
        long id=second.path("list").get(0).path("id").asLong();
        for(String field:List.of("title","category","summary","content")) {
            ObjectNode input=json.createObjectNode().put("revision",0).put("title","标题").put("category","终端").put("summary","摘要").put("content","步骤");
            input.put(field,"  ");
            mvc.perform(put("/kb/manage/"+id).contentType("application/json").content(input.toString()).header("Authorization","Bearer "+it)).andExpect(status().isBadRequest());
        }
        assertEquals(1,body(mvc.perform(get("/kb/manage").param("keyword","分页知识22").header("Authorization","Bearer "+it))).path("total").asInt());
        assertEquals(0,body(mvc.perform(get("/kb/manage").param("status","PUBLISHED").header("Authorization","Bearer "+it))).path("total").asInt());
    }
}
