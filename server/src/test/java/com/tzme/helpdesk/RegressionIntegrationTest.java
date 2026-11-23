package com.tzme.helpdesk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tzme.helpdesk.common.*;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.job.*;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.security.JwtUtil;
import com.tzme.helpdesk.service.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:helpdesk-regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts="/db/fixture.sql", config=@SqlConfig(encoding="UTF-8"))
class RegressionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired HelpdeskProperties props;
    @Autowired JwtUtil jwt;
    @Autowired AssetRequestService requests;
    @Autowired TicketService ticketService;
    @Autowired AssetService assetService;
    @Autowired javax.sql.DataSource dataSource;
    @MockBean SlaScanJob slaJob;
    @MockBean ContactSyncJob contactJob;
    @MockBean DingApprovalSyncJob dingApprovalJob;
    @TempDir Path uploads;

    @BeforeEach void setup() { props.getDingtalk().setEnabled(false); props.getUpload().setDir(uploads.toString()); }
    @AfterEach void cleanup() { CurrentUser.clear(); props.getDingtalk().setEnabled(false); }
    String login(String name) throws Exception {
        return body(mvc.perform(post("/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("demoUser",name)))))
                .get("token").asText();
    }
    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }
    MockMultipartFile file() { return new MockMultipartFile("file","screenshot.png","image/png",new byte[]{(byte)137,80,78,71}); }
    JsonNode upload(String token) throws Exception {
        return body(mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+token)));
    }
    JsonNode settings(String token) throws Exception { return body(mvc.perform(get("/admin/manage/settings").header("Authorization","Bearer "+token))); }

    @Test void completedEmergencyReportsRestrictNewRequestsAndCanBeSupplementedOrCancelled() throws Exception {
        String owner = login("张伟"), it = login("李强"), admin = login("陈静"), other = login("普通用户乙");
        String root = "/missing-reports";
        mvc.perform(registerMissingReport(owner, evidence())).andExpect(status().isForbidden());
        mvc.perform(registerMissingReport(it, new MockMultipartFile("evidence","evidence.png","image/png",new byte[0])))
                .andExpect(status().isBadRequest());
        mvc.perform(registerMissingReport(it, new MockMultipartFile("evidence","evidence.exe","application/octet-stream",new byte[]{1})))
                .andExpect(status().isBadRequest());
        assertEquals(0, db.queryForObject("select count(*) from it_missing_report", Integer.class));

        List<String> reports = new ArrayList<>();
        for (int i=0; i<3; i++) reports.add(body(mvc.perform(registerMissingReport(it, evidence()))).path("no").asText());
        JsonNode three = body(mvc.perform(get(root+"/summary").header("Authorization","Bearer "+owner)));
        assertEquals(3, three.path("pending").asInt());
        assertFalse(three.path("blocked").asBoolean());
        String ordinary = submitNotificationTicket(owner, "INCIDENT", "无法开机");
        JsonNode staffTicket = body(mvc.perform(get("/tickets/"+ordinary).header("Authorization","Bearer "+it)));
        assertEquals(3, staffTicket.path("ticket").path("pendingReportCount").asInt());
        db.update("delete from it_asset_request");
        JsonNode asset = body(mvc.perform(post("/asset-requests").contentType("application/json")
                .content("{\"type\":\"RECEIVE\",\"assetNo\":\"ASSET-TEST-1\",\"reason\":\"未达阈值\"}")
                .header("Authorization","Bearer "+owner)));
        assertEquals(asset.path("no"), body(mvc.perform(get("/asset-requests").header("Authorization","Bearer "+it))).get(0).path("no"));
        assertEquals(3, body(mvc.perform(get("/asset-requests").header("Authorization","Bearer "+it))).get(0).path("pendingReportCount").asInt());

        reports.add(body(mvc.perform(registerMissingReport(admin, evidence()))).path("no").asText());
        assertTrue(body(mvc.perform(get(root+"/summary").header("Authorization","Bearer "+owner))).path("blocked").asBoolean());
        String input = "{\"title\":\"补填报修\",\"description\":\"临时处理完成\",\"location\":\"三楼\",\"cat1\":\"终端\",\"cat2\":\"无法开机\"}";
        mvc.perform(post("/tickets").contentType("application/json").content(input).header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        mvc.perform(post("/asset-requests").contentType("application/json").content("{\"type\":\"RECEIVE\",\"assetNo\":\"ASSET-TEST-2\",\"reason\":\"测试锁定\"}")
                .header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        mvc.perform(post("/purchase-requests").contentType("application/json").content("{\"items\":[{\"catalogId\":1,\"qty\":1}],\"reason\":\"测试锁定\"}")
                .header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        mvc.perform(get("/assets").header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        assertEquals(4, body(mvc.perform(get(root+"/mine").header("Authorization","Bearer "+owner))).size());
        assertTrue(body(mvc.perform(get(root+"/mine").header("Authorization","Bearer "+other))).isEmpty());

        String no = reports.get(0);
        JsonNode detail = body(mvc.perform(get(root+"/"+no).header("Authorization","Bearer "+owner)));
        String stored = detail.path("evidence").get(0).path("storedName").asText();
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(post(root+"/"+no+"/submit").contentType("application/json").content(input).header("Authorization","Bearer "+other))
                .andExpect(status().isForbidden());
        JsonNode closed = body(mvc.perform(post(root+"/"+no+"/submit").contentType("application/json").content(input)
                .header("Authorization","Bearer "+owner)));
        assertEquals("CLOSED", closed.path("status").asText());
        String ticketNo = closed.path("no").asText();
        assertEquals(0, db.queryForObject("select count(*) from it_notify where ref_no=?", Integer.class, ticketNo));
        assertEquals(ticketNo, body(mvc.perform(post(root+"/"+no+"/submit").contentType("application/json")
                .content(input).header("Authorization","Bearer "+owner))).path("no").asText());
        assertFalse(body(mvc.perform(get(root+"/summary").header("Authorization","Bearer "+owner))).path("blocked").asBoolean());
        mvc.perform(get("/assets").header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        submitNotificationTicket(owner, "INCIDENT", "无法开机");

        String cancellation = root+"/"+reports.get(1)+"/cancel";
        String reason = "{\"reason\":\"需求人登记错误\"}";
        mvc.perform(post(cancellation).contentType("application/json").content(reason).header("Authorization","Bearer "+owner))
                .andExpect(status().isForbidden());
        body(mvc.perform(post(cancellation).contentType("application/json").content(reason).header("Authorization","Bearer "+it)));
        assertEquals(2, body(mvc.perform(get(root+"/summary").header("Authorization","Bearer "+owner))).path("pending").asInt());
        mvc.perform(get(root+"/"+reports.get(1)).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        assertEquals("CANCELLED", body(mvc.perform(get(root+"/"+reports.get(1)).header("Authorization","Bearer "+it))).path("report").path("status").asText());
    }

    private MockMultipartFile evidence() {
        return new MockMultipartFile("evidence","evidence.png","image/png",new byte[]{(byte)137,80,78,71});
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder registerMissingReport(String token, MockMultipartFile evidence) throws Exception {
        var registration = new MockMultipartFile("report","report.json","application/json",
                json.writeValueAsBytes(Map.of("requesterId",1,"title","紧急处理","description","已根据电话沟通处理完成",
                        "channel","PHONE","completedAt",java.time.LocalDateTime.now().minusMinutes(10))));
        var request = multipart("/missing-reports").file(registration).file(evidence);
        request.header("Authorization","Bearer "+token);
        return request;
    }

    @Test void incomingTicketNotificationsUseTheMostSpecificRuleAndTheSelectedAccount() throws Exception {
        String endpoint = "/admin/manage/incoming-notifications";
        String owner = login("张伟"), admin = login("陈静");
        db.update("update sys_user set ding_user_id=concat('test-ding-',id) where id in (1,2,3,4,5,6)");
        db.update("update sys_user set role='IT',name='同名工程师' where id=6");
        db.update("insert into sys_user(id,name,ding_user_id,dept_name,role,status) values"
                + "(16,'同名工程师','test-ding-16','研发部','USER','ACTIVE'),"
                + "(17,'未绑定工程师',null,'研发部','IT','ACTIVE'),"
                + "(18,'停用工程师','test-ding-18','研发部','IT','DISABLED')");
        String selected = jwt.create(new LoginUser(6L,"同名工程师","IT","技术中心"));
        String namesake = jwt.create(new LoginUser(16L,"同名工程师","USER","研发部"));
        mvc.perform(get(endpoint).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        JsonNode config = body(mvc.perform(get(endpoint).header("Authorization","Bearer "+admin)));
        Set<Long> eligibleIds = new HashSet<>();
        config.path("recipients").forEach(person -> {
            eligibleIds.add(person.path("id").asLong());
            assertTrue(Set.of("IT","ADMIN").contains(person.path("role").asText()));
            assertFalse(person.has("dingUserId"));
        });
        assertEquals(Set.of(3L,4L,5L,6L), eligibleIds);

        String rules = """
                {"revision":0,"ticketRules":[
                  {"type":"INCIDENT","cat1":"终端","recipientIds":[4,5]},
                  {"type":"INCIDENT","cat1":"终端","cat2":"无法开机","recipientIds":[6,6]}
                ],"assetRecipients":{}}
                """;
        mvc.perform(put(endpoint).contentType("application/json").content(rules).header("Authorization","Bearer "+owner))
                .andExpect(status().isForbidden());
        for (int id : List.of(1,2,17,18)) {
            mvc.perform(put(endpoint).contentType("application/json").content(rules.replace("[6,6]", "["+id+"]"))
                    .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        }
        JsonNode saved = body(mvc.perform(put(endpoint).contentType("application/json").content(rules).header("Authorization","Bearer "+admin)));
        assertEquals(1, saved.path("revision").asInt());
        assertEquals(1, saved.path("ticketRules").get(1).path("recipientIds").size());
        mvc.perform(put(endpoint).contentType("application/json").content(rules).header("Authorization","Bearer "+admin))
                .andExpect(status().isConflict());

        String exact = submitNotificationTicket(owner, "INCIDENT", "无法开机");
        assertEquals(List.of(6L), notificationRecipients(exact, "新工单待受理"));
        assertEquals(exact, body(mvc.perform(get("/notifies").header("Authorization","Bearer "+selected))).get(0).path("refNo").asText());
        assertTrue(body(mvc.perform(get("/notifies").header("Authorization","Bearer "+namesake))).isEmpty());
        long id = db.queryForObject("select id from it_notify where ref_no=? and recipient_id=6", Long.class, exact);
        body(mvc.perform(post("/notifies/"+id+"/read").header("Authorization","Bearer "+namesake)));
        assertEquals(1, body(mvc.perform(get("/notifies/unread-count").header("Authorization","Bearer "+selected))).path("count").asInt());
        body(mvc.perform(post("/notifies/read-all").header("Authorization","Bearer "+selected)));
        assertEquals(0, body(mvc.perform(get("/notifies/unread-count").header("Authorization","Bearer "+selected))).path("count").asInt());

        db.update("update sys_user set role='USER' where id=6");
        assertEquals(List.of(4L,5L), notificationRecipients(submitNotificationTicket(owner, "INCIDENT", "无法开机"), "新工单待受理"));
        assertEquals(List.of(4L,5L), notificationRecipients(submitNotificationTicket(owner, "INCIDENT", "硬件故障"), "新工单待受理"));
        JsonNode afterDowngrade = body(mvc.perform(get(endpoint).header("Authorization","Bearer "+admin)));
        assertEquals(1, afterDowngrade.path("rules").path("ticketRules").size());
        mvc.perform(put(endpoint).contentType("application/json").content(saved.toString())
                .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        body(mvc.perform(put(endpoint).contentType("application/json").content(afterDowngrade.path("rules").toString())
                .header("Authorization","Bearer "+admin)));
        String fallback = submitNotificationTicket(owner, "QUESTION", "设备使用");
        assertEquals(List.of(3L,4L,5L,17L), notificationRecipients(fallback, "新工单待受理"));
    }

    @Test void eachAssetRequestNotifiesItsOwnRecipientsIncludingDingTalkApproval() throws Exception {
        String owner = login("张伟"), admin = login("陈静");
        db.update("update sys_user set ding_user_id=concat('test-ding-',id) where id in (2,3,4,5,6)");
        db.update("update sys_user set role='IT' where id=6");
        db.update("delete from it_asset_request");
        db.update("insert into sys_setting(setting_key,setting_value) values('runtime',?)",
                "{\"approvalEnabled\":true,\"approvalMock\":true,\"approvalProcessCodes\":{\"REPLACE\":\"MOCK-TEST\"}}");
        String rules = "{\"revision\":0,\"ticketRules\":[],\"assetRecipients\":{\"RECEIVE\":[3],\"BORROW\":[4],\"REPLACE\":[5],\"RETURN\":[6]}}";
        mvc.perform(put("/admin/manage/incoming-notifications").contentType("application/json")
                .content(rules.replace("[3]","[2]")).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        body(mvc.perform(put("/admin/manage/incoming-notifications").contentType("application/json").content(rules)
                .header("Authorization","Bearer "+admin)));

        Map<String, Long> recipients = new LinkedHashMap<>();
        recipients.put("RECEIVE", 3L);
        recipients.put("BORROW", 4L);
        recipients.put("REPLACE", 5L);
        recipients.put("RETURN", 6L);
        for (var entry : recipients.entrySet()) {
            String type = entry.getKey(), no = "TEST-" + type;
            String state = switch (type) { case "REPLACE" -> "在用"; case "RETURN" -> "借出中"; default -> "闲置"; };
            db.update("insert into it_asset(no,name,category,status,user_name,borrower) values(?,?,'终端',?,?,?)",
                    no, "通知测试设备", state, "REPLACE".equals(type) ? "张伟" : null, "RETURN".equals(type) ? "张伟" : null);
            ObjectNode request = json.createObjectNode().put("type",type).put("assetNo",no).put("reason","来单通知回归");
            if ("BORROW".equals(type)) request.put("expectReturn", java.time.LocalDate.now().plusDays(1).toString());
            if ("REPLACE".equals(type)) request.put("newAssetNo", "ASSET-TEST-2");
            JsonNode created = body(mvc.perform(post("/asset-requests").contentType("application/json")
                    .content(request.toString()).header("Authorization","Bearer "+owner)));
            String requestNo = created.path("no").asText();
            assertEquals(List.of(entry.getValue()), notificationRecipients(requestNo, "有新的资产申请"));
            assertEquals(1, db.queryForObject("select count(*) from it_notify where ref_no=? and title='申请已提交' and to_user='张伟'", Integer.class, requestNo));
            assertEquals("REPLACE".equals(type), created.path("dingInstanceId").asText().startsWith("MOCK-"));
        }

        db.update("update sys_user set role='VIEWER' where id=6");
        db.update("insert into it_asset(no,name,category,status,borrower) values('TEST-RETURN-2','通知测试设备','终端','借出中','张伟')");
        JsonNode fallback = body(mvc.perform(post("/asset-requests").contentType("application/json")
                .content("{\"type\":\"RETURN\",\"assetNo\":\"TEST-RETURN-2\",\"reason\":\"已解绑原接收角色\"}")
                .header("Authorization","Bearer "+owner)));
        assertEquals(List.of(3L,4L,5L), notificationRecipients(fallback.path("no").asText(), "有新的资产申请"));
    }

    private String submitNotificationTicket(String token, String type, String child) throws Exception {
        ObjectNode request = json.createObjectNode().put("type",type).put("cat1","终端").put("cat2",child)
                .put("title","报修通知测试").put("location","二楼").put("description","报修分类通知回归验证");
        return body(mvc.perform(post("/tickets").contentType("application/json")
                .content(request.toString()).header("Authorization","Bearer "+token))).path("no").asText();
    }

    private List<Long> notificationRecipients(String no, String title) {
        return db.queryForList("select recipient_id from it_notify where ref_no=? and title=? and recipient_id is not null order by recipient_id",
                Long.class, no, title);
    }

    @Test void ticketCreationRequiresAnExplicitLocationEvenWhenTheDeviceHasOne() throws Exception {
        String owner = login("张伟");
        db.update("update it_asset set location='A座1楼备件库' where no='ASSET-TEST-1'");
        var request = new LinkedHashMap<String,Object>(Map.of("title", "必须填写故障位置", "description", "设备已经搬到另外一层", "cat1", "终端", "cat2", "无法开机", "assetNo", "ASSET-TEST-1"));
        for (String location : Arrays.asList(null, "", "   ", "\n\t", "字".repeat(65))) {
            if (location == null) request.remove("location"); else request.put("location", location);
            mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)).andExpect(status().isBadRequest());
        }
        assertEquals(1, db.queryForObject("select count(*) from it_ticket", Integer.class));
    }

    @Test void explicitFaultLocationIsSavedAndShownToBothReporterAndEngineer() throws Exception {
        String owner = login("张伟"), engineer = login("李强");
        db.update("update it_asset set location='A座1楼备件库' where no='ASSET-TEST-1'");
        for (boolean linked : List.of(false, true)) {
            var request = new LinkedHashMap<String,Object>(Map.of("title", "故障位置回归", "description", "设备移动后的实际位置", "cat1", "终端", "cat2", "无法开机", "location", "  B座3楼财务办公室，靠窗第2个工位  "));
            if (linked) request.put("assetNo", "ASSET-TEST-1");
            JsonNode ticket = body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)));
            String no = ticket.path("no").asText();
            assertEquals("B座3楼财务办公室，靠窗第2个工位", ticket.path("location").asText());
            assertEquals(ticket.path("location").asText(), db.queryForObject("select location from it_ticket where no=?", String.class, no));
            assertEquals(ticket.path("location"), userTicket(owner, no).path("location"));
            body(mvc.perform(post("/tickets/" + no + "/accept").contentType("application/json").content("{}").header("Authorization", "Bearer " + engineer)));
            assertEquals(ticket.path("location"), userTicket(engineer, no).path("location"));
        }
        var boundary = Map.of("title", "位置字数上限", "description", "六十四个字仍可保存", "cat1", "其他", "cat2", "其他", "location", "字".repeat(64));
        assertEquals(64, body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(boundary)).header("Authorization", "Bearer " + owner))).path("location").asText().length());
    }

    @Test void ticketTypesExposeDistinctCategoriesAndRejectMismatchedRequests() throws Exception {
        String owner = login("张伟");
        JsonNode dictionaries = body(mvc.perform(get("/meta/categories-by-type").header("Authorization", "Bearer " + owner)));
        assertEquals(Set.of("INCIDENT", "SERVICE_REQUEST", "QUESTION"), json.convertValue(dictionaries, Map.class).keySet());
        assertTrue(dictionaries.path("INCIDENT").path("终端").toString().contains("无法开机"));
        assertFalse(dictionaries.path("QUESTION").toString().contains("无法开机"));
        assertFalse(dictionaries.path("INCIDENT").toString().contains("账号开通"));
        assertFalse(dictionaries.path("SERVICE_REQUEST").toString().contains("蓝屏/死机"));
        for (String[] choice : List.of(new String[]{"QUESTION", "终端", "无法开机"}, new String[]{"SERVICE_REQUEST", "终端", "蓝屏/死机"},
                new String[]{"INCIDENT", "账号", "账号开通"}, new String[]{"UNKNOWN", "其他", "其他"})) {
            var request = Map.of("title", "非法类型分类组合", "description", "应拒绝不符合逻辑的组合", "location", "A座3楼测试区", "type", choice[0], "cat1", choice[1], "cat2", choice[2]);
            mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)).andExpect(status().isBadRequest());
        }
        assertEquals(1, db.queryForObject("select count(*) from it_ticket", Integer.class));
        for (String[] choice : List.of(new String[]{"INCIDENT", "终端", "无法开机"}, new String[]{"SERVICE_REQUEST", "账号", "账号开通"}, new String[]{"QUESTION", "终端", "设备使用"})) {
            var request = Map.of("title", "合法类型分类组合", "description", "可提交当前类型的对应分类", "location", "A座3楼测试区", "type", choice[0], "cat1", choice[1], "cat2", choice[2]);
            assertEquals(choice[0], body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner))).path("type").asText());
        }
    }

    @Test void typedDictionariesPersistEnforceRevisionAndLeaveHistoricalTicketsIntact() throws Exception {
        String admin = login("陈静"), owner = login("张伟");
        var historical = db.queryForMap("select * from it_ticket where no='IT-TEST-0001'");
        JsonNode current = body(mvc.perform(get("/admin/manage/dictionary").header("Authorization", "Bearer " + admin)));
        ObjectNode payload = json.createObjectNode().put("revision", current.path("revision").asLong());
        ObjectNode typed = current.path("categoriesByType").deepCopy();
        ((ObjectNode) typed.path("QUESTION")).set("专用系统", json.valueToTree(List.of("操作指导", "其他")));
        payload.set("categoriesByType", typed);
        mvc.perform(put("/admin/manage/categories").contentType("application/json").content(payload.toString()).header("Authorization", "Bearer " + owner)).andExpect(status().isForbidden());
        JsonNode saved = body(mvc.perform(put("/admin/manage/categories").contentType("application/json").content(payload.toString()).header("Authorization", "Bearer " + admin)));
        assertEquals(1, saved.path("revision").asLong());
        assertEquals("操作指导", body(mvc.perform(get("/meta/categories-by-type").header("Authorization", "Bearer " + owner))).path("QUESTION").path("专用系统").path(0).asText());
        assertEquals(historical, db.queryForMap("select * from it_ticket where no='IT-TEST-0001'"));
        mvc.perform(put("/admin/manage/categories").contentType("application/json").content(payload.toString()).header("Authorization", "Bearer " + admin)).andExpect(status().isConflict());
        payload.put("revision", saved.path("revision").asLong());
        ((ObjectNode) typed.path("INCIDENT")).remove("信息安全");
        mvc.perform(put("/admin/manage/categories").contentType("application/json").content(payload.toString()).header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
    }

    @Test void legacyCustomCategoriesMigrateToFaultsWithoutMixingFaultsIntoOtherTypes() throws Exception {
        var legacy = new RuntimeSettings();
        legacy.getCategories().put("自定义故障", List.of("设备异常", "其他"));
        legacy.getCategories().put("系统", List.of("软件安装", "自定义异常", "其他"));
        db.update("insert into sys_setting(setting_key,setting_value) values('runtime',?)", json.writeValueAsString(legacy));
        String owner = login("张伟");
        JsonNode typed = body(mvc.perform(get("/meta/categories-by-type").header("Authorization", "Bearer " + owner)));
        assertEquals("设备异常", typed.path("INCIDENT").path("自定义故障").path(0).asText());
        assertFalse(typed.path("INCIDENT").path("系统").toString().contains("软件安装"));
        assertFalse(typed.path("QUESTION").has("自定义故障"));
        assertEquals("软件安装", typed.path("SERVICE_REQUEST").path("系统").path(0).asText());
    }

    @Test void otherCategoryNotesAreSavedAndVisibleThroughoutHandling() throws Exception {
        String owner = login("张伟"), engineer = login("李强");
        for (String category : List.of("其他", "终端")) {
            var request = new LinkedHashMap<String,Object>(Map.of("title", "分类补充测试", "description", "测试选填的其他分类信息", "location", "A座3楼测试区", "cat1", category, "cat2", "其他", "categoryNote", "  会议室音视频  "));
            JsonNode created = body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)));
            String no = created.path("no").asText();
            assertEquals("会议室音视频", created.path("categoryNote").asText());
            assertEquals(category, created.path("cat1").asText());
            assertEquals("其他", created.path("cat2").asText());
            assertEquals("会议室音视频", db.queryForObject("select category_note from it_ticket where no=?", String.class, no));
            assertEquals("会议室音视频", userTicket(owner, no).path("categoryNote").asText());
            body(mvc.perform(post("/tickets/" + no + "/accept").contentType("application/json").content("{}").header("Authorization", "Bearer " + engineer)));
            assertEquals("会议室音视频", userTicket(engineer, no).path("categoryNote").asText());
        }
    }

    @Test void otherCategoryNoteCanBeOmittedOrBlankAndHasALengthLimit() throws Exception {
        String owner = login("张伟");
        var request = new LinkedHashMap<String,Object>(Map.of("title", "不补充分类也能报修", "description", "选择其他但不填写具体分类", "location", "A座3楼测试区", "cat1", "其他", "cat2", "其他"));
        for (String note : Arrays.asList(null, "", "   ")) {
            if (note == null) request.remove("categoryNote"); else request.put("categoryNote", note);
            JsonNode created = body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)));
            assertFalse(created.hasNonNull("categoryNote"));
        }
        request.put("categoryNote", "字".repeat(200));
        assertEquals(200, body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner))).path("categoryNote").asText().length());
        request.put("categoryNote", "字".repeat(201));
        mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner)).andExpect(status().isBadRequest());
        request.put("cat1", "终端"); request.put("cat2", "无法开机"); request.put("categoryNote", "不应带入的旧内容");
        assertFalse(body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(request)).header("Authorization", "Bearer " + owner))).hasNonNull("categoryNote"));
    }

    @Test void legacyCategoryNoteUpgradePreservesTicketsAndExistingNotes() {
        db.update("update it_ticket set assigned_at=created_at where no='IT-TEST-0001'");
        var before = db.queryForMap("select * from it_ticket where no='IT-TEST-0001'");
        db.execute("alter table it_ticket drop column category_note");
        var initializer = new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource);
        initializer.afterPropertiesSet();
        assertEquals(before, db.queryForMap("select * from it_ticket where no='IT-TEST-0001'"));
        db.update("update it_ticket set category_note='已有分类补充' where no='IT-TEST-0001'");
        before = db.queryForMap("select * from it_ticket where no='IT-TEST-0001'");
        initializer.afterPropertiesSet();
        assertEquals(before, db.queryForMap("select * from it_ticket where no='IT-TEST-0001'"));
    }

    @Test void legacyDatabaseGetsAttachmentsWithoutChangingTicketsOrExistingFiles() throws Exception {
        String owner = login("张伟"), other = login("普通用户乙");
        db.update("update it_ticket set assigned_at=created_at where no='IT-TEST-0001'");
        var ticketBefore = db.queryForMap("select * from it_ticket where no='IT-TEST-0001'");
        db.execute("drop table it_attachment");
        var initializer = new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource);
        initializer.afterPropertiesSet();
        assertEquals(ticketBefore, db.queryForMap("select * from it_ticket where no='IT-TEST-0001'"));
        assertEquals("IT-TEST-0001", body(mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization", "Bearer " + owner))).path("ticket").path("no").asText());
        assertTrue(body(mvc.perform(get("/attachments").param("bizType", "TICKET").param("bizNo", "IT-TEST-0001").header("Authorization", "Bearer " + owner))).isEmpty());
        JsonNode attachment = upload(owner);
        var attachmentBefore = db.queryForList("select * from it_attachment");
        initializer.afterPropertiesSet();
        assertEquals(attachmentBefore, db.queryForList("select * from it_attachment"));
        mvc.perform(get("/attachments/raw/" + attachment.get("storedName").asText()).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
        mvc.perform(get("/attachments").param("bizType", "TICKET").param("bizNo", "IT-TEST-0001").header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
    }

    @Test void enablingDingTalkDisablesDemoEndpointsAndPreviouslyIssuedDemoSessions() throws Exception {
        String demo = login("陈静");
        assertTrue(body(mvc.perform(get("/auth/config"))).get("demoEnabled").asBoolean());
        props.getDingtalk().setEnabled(true);
        assertFalse(body(mvc.perform(get("/auth/config"))).get("demoEnabled").asBoolean());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + demo)).andExpect(status().isUnauthorized());
        mvc.perform(get("/tickets").header("X-Demo-User", "陈静")).andExpect(status().isUnauthorized());
        mvc.perform(get("/meta/service-contacts/display")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content("{\"demoUser\":\"陈静\"}")).andExpect(status().isUnauthorized());
    }

    @Test void assetQrLabelsUseAValidOperationRouteAndEncodeTheInventoryNumber() throws Exception {
        String original = props.getQrcode().getBaseUrl();
        props.getQrcode().setBaseUrl("https://assets.example/helpdesk/");
        try {
            String user = login("张伟");
            JsonNode label = body(mvc.perform(get("/assets/ASSET-TEST-1/qrcode").header("Authorization", "Bearer " + user)));
            assertEquals("https://assets.example/helpdesk/#/assets?asset=ASSET-TEST-1", label.get("content").asText());
            db.update("insert into it_asset(no,name,category,status) values('QR A&B+1','特殊编号设备','终端','闲置')");
            assertEquals("https://assets.example/helpdesk/#/assets?asset=QR+A%26B%2B1", assetService.qrcode("QR A&B+1"));
        } finally { props.getQrcode().setBaseUrl(original); }
    }

    @Test void dingTalkSessionsContinueToFollowDatabaseRoles() throws Exception {
        props.getDingtalk().setEnabled(true);
        String token = new com.tzme.helpdesk.security.JwtUtil(props).create(new LoginUser(3L, "陈静", "ADMIN", "技术中心"));
        assertEquals("陈静", body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))).get("name").asText());
        db.update("update sys_user set role='USER' where id=3");
        mvc.perform(get("/admin/manage/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    void seedAssetPages() {
        for (int index = 0; index < 35; index++) db.update("insert into it_asset(no,name,category,status,sn,user_name) values(?,?,?,?,?,?)",
                "PAGE-" + String.format("%03d", index), "分页资产 " + index, index % 2 == 0 ? "终端" : "打印复印",
                index % 3 == 0 ? "在用" : "闲置", "SERIAL-" + index, index % 3 == 0 ? "张伟" : null);
    }

    @Test void assetLedgerPagesKeepStableOrderAndFullListContract() throws Exception {
        seedAssetPages();
        String viewer = login("赵敏");
        JsonNode full = body(mvc.perform(get("/assets").header("Authorization", "Bearer " + viewer)));
        assertTrue(full.isArray()); assertEquals(37, full.size());
        List<String> expected = new ArrayList<>(), actual = new ArrayList<>();
        full.forEach(asset -> expected.add(asset.get("no").asText()));
        for (int page = 1; page <= 3; page++) {
            JsonNode result = body(mvc.perform(get("/assets").param("page", String.valueOf(page)).param("size", "15")
                    .header("Authorization", "Bearer " + viewer)));
            assertEquals(37, result.get("total").asInt());
            assertEquals(page, result.get("page").asInt()); assertEquals(15, result.get("size").asInt());
            assertEquals(page < 3 ? 15 : 7, result.get("list").size());
            result.get("list").forEach(asset -> actual.add(asset.get("no").asText()));
        }
        assertEquals(expected, actual);
        assertEquals(37, new HashSet<>(actual).size());
        db.update("insert into it_asset(no,name,category,status) values('page','名称恰好为分页关键字的设备','终端','闲置')");
        assertEquals("page", body(mvc.perform(get("/assets/page").header("Authorization", "Bearer " + viewer))).get("asset").get("no").asText());
    }

    @Test void assetLedgerPagingCombinesFiltersAndBoundsPageSizes() throws Exception {
        seedAssetPages(); String viewer = login("赵敏");
        JsonNode filtered = body(mvc.perform(get("/assets").param("status", "在用").param("category", "终端")
                .param("keyword", "张伟").header("Authorization", "Bearer " + viewer)));
        JsonNode paged = body(mvc.perform(get("/assets").param("page", "1").param("size", "2").param("status", "在用")
                .param("category", "终端").param("keyword", "张伟").header("Authorization", "Bearer " + viewer)));
        assertEquals(filtered.size(), paged.get("total").asInt()); assertEquals(2, paged.get("list").size());
        paged.get("list").forEach(asset -> { assertEquals("在用", asset.get("status").asText()); assertEquals("终端", asset.get("category").asText()); });
        JsonNode serial = body(mvc.perform(get("/assets").param("page", "1").param("keyword", "SERIAL-34")
                .header("Authorization", "Bearer " + viewer)));
        assertEquals(1, serial.get("total").asInt()); assertEquals("PAGE-034", serial.get("list").get(0).get("no").asText());
        JsonNode minimum = body(mvc.perform(get("/assets").param("page", "-3").param("size", "0").header("Authorization", "Bearer " + viewer)));
        assertEquals(1, minimum.get("page").asInt()); assertEquals(1, minimum.get("size").asInt()); assertEquals(1, minimum.get("list").size());
        JsonNode maximum = body(mvc.perform(get("/assets").param("page", "1").param("size", "999").header("Authorization", "Bearer " + viewer)));
        assertEquals(100, maximum.get("size").asInt()); assertEquals(37, maximum.get("list").size());
        JsonNode beyond = body(mvc.perform(get("/assets").param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100")
                .header("Authorization", "Bearer " + viewer)));
        assertEquals(37, beyond.get("total").asInt()); assertTrue(beyond.get("list").isEmpty());
        JsonNode empty = body(mvc.perform(get("/assets").param("page", "1").param("keyword", "不存在的设备")
                .header("Authorization", "Bearer " + viewer)));
        assertEquals(0, empty.get("total").asInt()); assertTrue(empty.get("list").isEmpty());
    }

    @Test void pagedAssetReadUsesTheSameRolePermissionAsFullAssetRead() throws Exception {
        String admin = login("陈静"), viewer = login("赵敏");
        body(mvc.perform(get("/assets").param("page", "1").header("Authorization", "Bearer " + viewer)));
        ObjectNode config = policy(admin); removePermission(config, "VIEWER", "request.create"); removePermission(config, "VIEWER", "asset.view"); savePolicy(admin, config);
        mvc.perform(get("/assets").param("page", "1").header("Authorization", "Bearer " + viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/assets").header("Authorization", "Bearer " + viewer)).andExpect(status().isForbidden());
    }

    void assetRepairTicket(String no, String assetNo, long creator, String state) {
        db.update("insert into it_ticket(no,title,description,cat1,creator_id,creator_name,status,asset_no) values(?,?,?,?,?,?,?,?)",
                no, "设备报修明细-" + no, "内容提及 ASSET-TEST-1 不代表属于该设备", "终端", creator,
                creator == 1L ? "张伟" : "普通用户乙", state, assetNo);
    }

    @Test void deviceRepairFilterMatchesExactAssetNumberAndPaginatesAllHistory() throws Exception {
        db.update("update it_ticket set asset_no='ASSET-TEST-1' where no='IT-TEST-0001'");
        for (int index = 0; index < 12; index++) assetRepairTicket("REPORT-" + index, "ASSET-TEST-1", 6L, index % 2 == 0 ? "NEW" : "CLOSED");
        assetRepairTicket("REPORT-PREFIX", "ASSET-TEST-10", 6L, "NEW");
        assetRepairTicket("REPORT-UNRELATED", "ASSET-TEST-2", 6L, "NEW");
        assetRepairTicket("REPORT-UNASSOCIATED", null, 6L, "NEW");
        String viewer = login("赵敏");
        Set<String> numbers = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            JsonNode result = body(mvc.perform(get("/tickets").param("assetNo", "ASSET-TEST-1").param("page", String.valueOf(page))
                    .param("size", "10").header("Authorization", "Bearer " + viewer)));
            assertEquals(13, result.get("total").asInt());
            assertEquals(page == 1 ? 10 : 3, result.get("list").size());
            result.get("list").forEach(ticket -> {
                assertEquals("ASSET-TEST-1", ticket.get("assetNo").asText());
                assertTrue(numbers.add(ticket.get("no").asText()));
            });
        }
        assertEquals(13, numbers.size());
        JsonNode ranking = body(mvc.perform(get("/stats/problem-assets").header("Authorization", "Bearer " + viewer)));
        assertEquals("ASSET-TEST-1", ranking.get(0).get("no").asText());
        assertEquals(13, ranking.get(0).get("times").asInt());
    }

    @Test void deviceRepairFilterKeepsUserScopeAndCombinesWithStatusAndKeyword() throws Exception {
        db.update("update it_ticket set asset_no='ASSET-TEST-1' where no='IT-TEST-0001'");
        assetRepairTicket("REPORT-NEW", "ASSET-TEST-1", 6L, "NEW");
        assetRepairTicket("REPORT-CLOSED", "ASSET-TEST-1", 6L, "CLOSED");
        String owner = login("张伟"), other = login("普通用户乙"), viewer = login("赵敏");
        JsonNode scoped = body(mvc.perform(get("/tickets").param("assetNo", "ASSET-TEST-1").header("Authorization", "Bearer " + owner)));
        assertEquals(1, scoped.get("total").asInt());
        assertEquals("IT-TEST-0001", scoped.get("list").get(0).get("no").asText());
        assertEquals(2, body(mvc.perform(get("/tickets").param("assetNo", "ASSET-TEST-1").header("Authorization", "Bearer " + other))).get("total").asInt());
        JsonNode combined = body(mvc.perform(get("/tickets").param("assetNo", "ASSET-TEST-1").param("status", "CLOSED")
                .param("keyword", "REPORT-CLOSED").header("Authorization", "Bearer " + viewer)));
        assertEquals(1, combined.get("total").asInt());
        assertEquals("REPORT-CLOSED", combined.get("list").get(0).get("no").asText());
        for (String assetNo : List.of("NOT-FOUND", "ASSET-TEST-1' OR 1=1 --"))
            assertEquals(0, body(mvc.perform(get("/tickets").param("assetNo", assetNo).header("Authorization", "Bearer " + viewer))).get("total").asInt());
    }

    @Test void assetStatisticDetailsMatchExactStatesAndRespectReadPermissions() throws Exception {
        db.update("update it_asset set status='在用',user_name='张伟' where no='ASSET-TEST-1'");
        String viewer = login("赵敏"), admin = login("陈静");
        JsonNode overview = body(mvc.perform(get("/stats/overview").header("Authorization", "Bearer " + viewer)));
        assertEquals(overview.get("assetTotal").asInt(), body(mvc.perform(get("/assets").header("Authorization", "Bearer " + viewer))).size());
        for (var filter : Map.of("在用", "assetInUse", "闲置", "assetIdle", "借出中", "assetBorrowed").entrySet()) {
            JsonNode assets = body(mvc.perform(get("/assets").param("status", filter.getKey()).header("Authorization", "Bearer " + viewer)));
            assertEquals(overview.get(filter.getValue()).asInt(), assets.size());
            assets.forEach(asset -> assertEquals(filter.getKey(), asset.get("status").asText()));
        }
        ObjectNode config = policy(admin); removePermission(config, "VIEWER", "request.create"); removePermission(config, "VIEWER", "asset.view"); savePolicy(admin, config);
        mvc.perform(get("/assets").header("Authorization", "Bearer " + viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/assets/ASSET-TEST-1").header("Authorization", "Bearer " + viewer)).andExpect(status().isForbidden());
        body(mvc.perform(get("/stats/overview").header("Authorization", "Bearer " + viewer)));
    }

    void prepareUnclaimedTicket(String state) {
        db.update("update it_ticket set status=?,assignee_id=null,assignee_name=null,accepted_at=null,assigned_at=null where no='IT-TEST-0001'", state);
    }

    @Test void acceptingAssignsCurrentEngineerAndStartsHandlingWithoutDispatchPermission() throws Exception {
        prepareUnclaimedTicket("NEW");
        queueTicket("CLAIM-EARLIER", "IN_PROGRESS", 4L, java.time.LocalDateTime.now().minusHours(1), 6L);
        String admin = login("陈静"), engineer = login("李强");
        ObjectNode config = policy(admin); removePermission(config, "IT", "ticket.dispatch"); savePolicy(admin, config);
        JsonNode accepted = body(mvc.perform(post("/tickets/IT-TEST-0001/accept").contentType("application/json")
                .content("{\"remark\":\"开始排查\",\"assigneeId\":5}").header("Authorization", "Bearer " + engineer)));
        assertEquals("IN_PROGRESS", accepted.get("status").asText());
        assertEquals(4, accepted.get("assigneeId").asInt());
        assertEquals("李强", accepted.get("assigneeName").asText());
        assertTrue(accepted.hasNonNull("acceptedAt"));
        assertEquals(accepted.get("acceptedAt"), accepted.get("assignedAt"));
        assertEquals(1, accepted.get("queueAhead").asInt());
        JsonNode detail = body(mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization", "Bearer " + engineer)));
        assertTrue(detail.get("actions").toString().contains("RECORD"));
        assertTrue(detail.get("actions").toString().contains("FINISH"));
        assertFalse(detail.get("actions").toString().contains("DISPATCH"));
        assertEquals(1, detail.get("events").size());
        assertTrue(detail.get("events").get(0).get("remark").asText().contains("李强；开始排查"));
        assertTrue(db.queryForObject("select content from it_notify where ref_no='IT-TEST-0001' and to_user='张伟'", String.class).contains("李强"));
        mvc.perform(post("/tickets/IT-TEST-0001/accept").header("Authorization", "Bearer " + engineer)).andExpect(status().isBadRequest());
        assertEquals(4, userTicket(engineer, "IT-TEST-0001").get("assigneeId").asInt());
    }

    @Test void acceptingRequiresHandlingCapabilityWhileRejectRemainsAvailable() throws Exception {
        prepareUnclaimedTicket("NEW");
        String admin = login("陈静"), engineer = login("李强");
        ObjectNode config = policy(admin); removePermission(config, "IT", "ticket.handle"); savePolicy(admin, config);
        JsonNode detail = body(mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization", "Bearer " + engineer)));
        assertFalse(detail.get("actions").toString().contains("ACCEPT"));
        assertTrue(detail.get("actions").toString().contains("REJECT"));
        mvc.perform(post("/tickets/IT-TEST-0001/accept").header("Authorization", "Bearer " + engineer)).andExpect(status().isForbidden());
        assertEquals("NEW", db.queryForObject("select status from it_ticket where no='IT-TEST-0001'", String.class));
        assertNull(db.queryForObject("select assignee_id from it_ticket where no='IT-TEST-0001'", Long.class));
    }

    @Test void legacyAcceptedTicketCanBeClaimedWithoutOverwritingAnotherEngineer() throws Exception {
        prepareUnclaimedTicket("ACCEPTED");
        var original = java.time.LocalDateTime.now().minusHours(2).withNano(0);
        db.update("update it_ticket set accepted_at=? where no='IT-TEST-0001'", original);
        String engineer = login("李强");
        JsonNode detail = body(mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization", "Bearer " + engineer)));
        assertTrue(detail.get("actions").toString().contains("ACCEPT"));
        assertFalse(detail.get("actions").toString().contains("DISPATCH"));
        JsonNode accepted = body(mvc.perform(post("/tickets/IT-TEST-0001/accept").header("Authorization", "Bearer " + engineer)));
        assertEquals("IN_PROGRESS", accepted.get("status").asText());
        assertEquals(4, accepted.get("assigneeId").asInt());
        assertEquals(original, db.queryForObject("select accepted_at from it_ticket where no='IT-TEST-0001'", java.sql.Timestamp.class).toLocalDateTime());
        db.update("update it_ticket set status='ACCEPTED',assignee_id=5,assignee_name='王敏' where no='IT-TEST-0001'");
        mvc.perform(post("/tickets/IT-TEST-0001/accept").header("Authorization", "Bearer " + engineer)).andExpect(status().isConflict());
        assertEquals(5, db.queryForObject("select assignee_id from it_ticket where no='IT-TEST-0001'", Long.class));
    }

    @Test void simultaneousEngineersCannotBothClaimTheSameTicket() throws Exception {
        prepareUnclaimedTicket("NEW");
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (long id : List.of(4L, 5L)) results.add(pool.submit(() -> {
                CurrentUser.set(new LoginUser(id, id == 4L ? "李强" : "王敏", "IT", "技术中心"));
                ready.countDown(); go.await();
                try { return ticketService.accept("IT-TEST-0001", null).getAssigneeId(); }
                catch (BizException expected) { return null; }
                finally { CurrentUser.clear(); }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            List<Long> winners = new ArrayList<>();
            for (Future<Long> result : results) { Long id = result.get(15, TimeUnit.SECONDS); if (id != null) winners.add(id); }
            assertEquals(1, winners.size());
            assertEquals(winners.get(0), db.queryForObject("select assignee_id from it_ticket where no='IT-TEST-0001'", Long.class));
            assertEquals("IN_PROGRESS", db.queryForObject("select status from it_ticket where no='IT-TEST-0001'", String.class));
            assertEquals(1, db.queryForObject("select count(*) from it_ticket_event where action='ACCEPT'", Integer.class));
            assertEquals(1, db.queryForObject("select count(*) from it_notify where ref_no='IT-TEST-0001' and to_user='张伟'", Integer.class));
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test void acceptingAndManualDispatchCannotOverwriteEachOther() throws Exception {
        prepareUnclaimedTicket("NEW");
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> claimed = pool.submit(() -> {
                CurrentUser.set(new LoginUser(4L, "李强", "IT", "技术中心")); ready.countDown(); go.await();
                try { ticketService.accept("IT-TEST-0001", null); return true; }
                catch (BizException expected) { return false; }
                finally { CurrentUser.clear(); }
            });
            Future<Boolean> dispatched = pool.submit(() -> {
                CurrentUser.set(new LoginUser(3L, "陈静", "ADMIN", "技术中心")); ready.countDown(); go.await();
                try { DispatchReq req = new DispatchReq(); req.setAssigneeId(5L); ticketService.dispatch("IT-TEST-0001", req); return true; }
                catch (BizException expected) { return false; }
                finally { CurrentUser.clear(); }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            boolean claimWon = claimed.get(15, TimeUnit.SECONDS), dispatchWon = dispatched.get(15, TimeUnit.SECONDS);
            assertNotEquals(claimWon, dispatchWon);
            assertEquals(claimWon ? 4L : 5L, db.queryForObject("select assignee_id from it_ticket where no='IT-TEST-0001'", Long.class));
            assertEquals(1, db.queryForObject("select count(*) from it_ticket_event", Integer.class));
            assertEquals(1, db.queryForObject("select count(*) from it_notify where ref_no='IT-TEST-0001' and to_user='张伟'", Integer.class));
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    void queueTicket(String no, String state, Long assignee, java.time.LocalDateTime assigned, long creator) {
        db.update("insert into it_ticket(no,title,description,cat1,creator_id,creator_name,status,assignee_id,assignee_name,assigned_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                no, "其他人的私人工单-" + no, "排队统计回归", "终端", creator, creator == 1 ? "张伟" : "普通用户乙", state,
                assignee, assignee == null ? null : assignee == 4 ? "李强" : "王敏", assigned, assigned.minusDays(1));
    }
    JsonNode userTicket(String token, String no) throws Exception {
        return body(mvc.perform(get("/tickets/" + no).header("Authorization", "Bearer " + token))).get("ticket");
    }

    @Test void userSeesOnlyCountOfEarlierUnfinishedWorkForCurrentEngineer() throws Exception {
        var assigned = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        db.update("update it_ticket set assigned_at=? where no='IT-TEST-0001'", assigned);
        for (String state : List.of("ACCEPTED", "IN_PROGRESS", "PENDING_USER", "PENDING_VENDOR"))
            queueTicket("QUEUE-" + state, state, 4L, assigned.minusHours(1), 6L);
        for (String state : List.of("PENDING_CONFIRM", "CLOSED", "REJECTED", "CANCELLED"))
            queueTicket("QUEUE-" + state, state, 4L, assigned.minusHours(2), 6L);
        queueTicket("QUEUE-LATER", "IN_PROGRESS", 4L, assigned.plusHours(1), 6L);
        db.update("update it_ticket set priority='P1',created_at=?,due_at=? where no='QUEUE-LATER'", assigned.minusDays(9), assigned.minusHours(5));
        queueTicket("QUEUE-OTHER-IT", "IN_PROGRESS", 5L, assigned.minusHours(3), 6L);
        queueTicket("QUEUE-UNASSIGNED", "NEW", null, assigned.minusHours(3), 6L);
        String owner = login("张伟");
        JsonNode ticket = userTicket(owner, "IT-TEST-0001");
        assertEquals(4, ticket.get("queueAhead").asInt());
        JsonNode page = body(mvc.perform(get("/tickets").param("mine", "true").param("size", "1").header("Authorization", "Bearer " + owner)));
        assertEquals(1, page.get("total").asInt());
        assertEquals(4, page.get("list").get(0).get("queueAhead").asInt());
        assertFalse(page.toString().contains("其他人的私人工单"));
        mvc.perform(get("/tickets/QUEUE-ACCEPTED").header("Authorization", "Bearer " + owner)).andExpect(status().isForbidden());
    }

    @Test void queueShrinksOnCompletionAndStopsForFinishedOrUnassignedWork() throws Exception {
        var assigned = java.time.LocalDateTime.now().minusHours(2);
        queueTicket("QUEUE-EARLIER", "IN_PROGRESS", 4L, assigned.minusHours(1), 6L);
        db.update("update it_ticket set assigned_at=? where no='IT-TEST-0001'", assigned);
        String owner = login("张伟"), engineer = login("李强");
        assertEquals(1, userTicket(owner, "IT-TEST-0001").get("queueAhead").asInt());
        body(mvc.perform(post("/tickets/QUEUE-EARLIER/finish").contentType("application/json")
                .content("{\"cat1\":\"终端\",\"cat2\":\"无法开机\",\"note\":\"前一单处理完成\"}").header("Authorization", "Bearer " + engineer)));
        assertEquals(0, userTicket(owner, "IT-TEST-0001").get("queueAhead").asInt());
        for (String state : List.of("PENDING_CONFIRM", "CLOSED", "REJECTED", "CANCELLED")) {
            db.update("update it_ticket set status=? where no='IT-TEST-0001'", state);
            assertFalse(userTicket(owner, "IT-TEST-0001").hasNonNull("queueAhead"));
        }
        db.update("update it_ticket set status='ACCEPTED',assignee_id=null,assignee_name=null where no='IT-TEST-0001'");
        assertFalse(userTicket(owner, "IT-TEST-0001").hasNonNull("queueAhead"));
    }

    @Test void equalAssignmentTimesUseStableTicketOrderAndLegacyDatesFallback() throws Exception {
        var assigned = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        db.update("update it_ticket set assigned_at=null,accepted_at=? where no='IT-TEST-0001'", assigned);
        queueTicket("QUEUE-TIE-1", "IN_PROGRESS", 4L, assigned, 1L);
        queueTicket("QUEUE-TIE-2", "IN_PROGRESS", 4L, assigned, 1L);
        String owner = login("张伟");
        assertEquals(0, userTicket(owner, "IT-TEST-0001").get("queueAhead").asInt());
        assertEquals(1, userTicket(owner, "QUEUE-TIE-1").get("queueAhead").asInt());
        assertEquals(2, userTicket(owner, "QUEUE-TIE-2").get("queueAhead").asInt());
        db.update("update it_ticket set assigned_at=null,accepted_at=null,created_at=? where no='QUEUE-TIE-2'", assigned.minusHours(1));
        assertEquals(1, userTicket(owner, "IT-TEST-0001").get("queueAhead").asInt());
    }

    @Test void queueCountsAllAssignedWorkBeyondVisiblePage() throws Exception {
        var assigned = java.time.LocalDateTime.now().minusHours(2);
        db.update("update it_ticket set assigned_at=? where no='IT-TEST-0001'", assigned);
        for (int i = 0; i < 105; i++) queueTicket("QUEUE-BULK-" + i, "IN_PROGRESS", 4L, assigned.minusMinutes(1), 6L);
        String owner = login("张伟");
        JsonNode page = body(mvc.perform(get("/tickets").param("page", "1").param("size", "1").header("Authorization", "Bearer " + owner)));
        assertEquals(1, page.get("total").asInt());
        assertEquals(105, page.get("list").get(0).get("queueAhead").asInt());
    }

    @Test void dispatchAndHandoverSetQueueTimeForCurrentEngineer() throws Exception {
        String admin = login("陈静"), owner = login("张伟");
        JsonNode created = body(mvc.perform(post("/tickets").contentType("application/json")
                .content("{\"title\":\"派单队列测试\",\"description\":\"记录当前 IT 的接单时间\",\"location\":\"A座3楼测试区\",\"cat1\":\"终端\",\"impact\":\"SELF\",\"workImpact\":\"SLOW\"}")
                .header("Authorization", "Bearer " + owner)));
        body(mvc.perform(post("/tickets/" + created.get("no").asText() + "/dispatch").contentType("application/json")
                .content("{\"assigneeId\":5}").header("Authorization", "Bearer " + admin)));
        assertTrue(userTicket(owner, created.get("no").asText()).hasNonNull("assignedAt"));
        db.update("update it_ticket set assigned_at=? where no=?", java.time.LocalDateTime.now().minusHours(1), created.get("no").asText());
        db.update("update it_ticket set assigned_at=? where no='IT-TEST-0001'", java.time.LocalDateTime.now().minusDays(1));
        body(mvc.perform(put("/admin/manage/users/4").contentType("application/json")
                .content("{\"role\":\"IT\",\"status\":\"DISABLED\",\"deptId\":1,\"handoverId\":5}").header("Authorization", "Bearer " + admin)));
        JsonNode moved = userTicket(owner, "IT-TEST-0001");
        assertEquals(5, moved.get("assigneeId").asInt());
        assertEquals(1, moved.get("queueAhead").asInt());
        assertTrue(db.queryForObject("select assigned_at from it_ticket where no='IT-TEST-0001'", java.sql.Timestamp.class)
                .toLocalDateTime().isAfter(java.time.LocalDateTime.now().minusMinutes(1)));
    }

    @Test void upgradeAddsQueueColumnAndPreservesHistoricalDispatchTime() throws Exception {
        var dispatched = java.time.LocalDateTime.of(2026, 1, 1, 11, 0);
        db.update("update it_ticket set accepted_at=?,updated_at=? where no='IT-TEST-0001'", dispatched.minusHours(1), dispatched.plusHours(1));
        db.update("insert into it_ticket_event(ticket_no,action,created_at) values('IT-TEST-0001','DISPATCH',?)", dispatched);
        db.execute("alter table it_ticket drop column assigned_at");
        new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource).afterPropertiesSet();
        assertEquals(dispatched, db.queryForObject("select assigned_at from it_ticket where no='IT-TEST-0001'", java.sql.Timestamp.class).toLocalDateTime());
        assertEquals(dispatched.plusHours(1), db.queryForObject("select updated_at from it_ticket where no='IT-TEST-0001'", java.sql.Timestamp.class).toLocalDateTime());
        new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource).afterPropertiesSet();
        assertEquals(dispatched, db.queryForObject("select assigned_at from it_ticket where no='IT-TEST-0001'", java.sql.Timestamp.class).toLocalDateTime());
    }

    void seedDashboardTickets() {
        for (String state : List.of("NEW", "ACCEPTED", "IN_PROGRESS", "PENDING_USER", "PENDING_VENDOR",
                "PENDING_CONFIRM", "CLOSED", "REJECTED", "CANCELLED")) {
            long creator = "PENDING_VENDOR".equals(state) ? 6L : 1L;
            db.update("insert into it_ticket(no,title,description,cat1,creator_id,creator_name,status,due_at) values(?,?,?,?,?,?,?,?)",
                    "KPI-" + state, "工作台筛选-" + state, "筛选回归专用工单", "终端", creator,
                    creator == 1L ? "张伟" : "普通用户乙", state, java.time.LocalDateTime.now().minusHours(1));
        }
    }

    @Test void processingCardFilterMatchesOverviewAcrossPages() throws Exception {
        seedDashboardTickets();
        String admin = login("陈静");
        Set<String> numbers = new HashSet<>();
        Set<String> statuses = new HashSet<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode result = body(mvc.perform(get("/tickets").param("status", "PROCESSING")
                    .param("page", String.valueOf(page)).param("size", "2").header("Authorization", "Bearer " + admin)));
            assertEquals(5, result.get("total").asInt());
            assertEquals(page < 3 ? 2 : 1, result.get("list").size());
            result.get("list").forEach(ticket -> {
                assertTrue(numbers.add(ticket.get("no").asText()), "分页结果不应重复");
                statuses.add(ticket.get("status").asText());
            });
        }
        assertEquals(Set.of("ACCEPTED", "IN_PROGRESS", "PENDING_USER", "PENDING_VENDOR"), statuses);
        JsonNode overview = body(mvc.perform(get("/stats/overview").header("Authorization", "Bearer " + admin)));
        assertEquals(overview.get("processing").asInt(), numbers.size());
        JsonNode exact = body(mvc.perform(get("/tickets").param("status", "IN_PROGRESS").header("Authorization", "Bearer " + admin)));
        assertEquals(2, exact.get("total").asInt(), "精确状态查询仍应可用");
    }

    @Test void overdueCardFilterUsesDeadlineAndExcludesEndedTickets() throws Exception {
        seedDashboardTickets();
        db.update("insert into it_ticket(no,title,description,cat1,creator_id,creator_name,status,due_at) values(?,?,'超时边界回归',?,?,?,?,?)",
                "KPI-RECENT", "近一分钟超时", "终端", 1L, "张伟", "IN_PROGRESS", java.time.LocalDateTime.now().minusSeconds(20));
        db.update("insert into it_ticket(no,title,description,cat1,creator_id,creator_name,status) values('KPI-NO-DUE','没有时限','无时限回归','终端',1,'张伟','NEW')");
        String admin = login("陈静");
        JsonNode result = body(mvc.perform(get("/tickets").param("status", "OVERDUE").header("Authorization", "Bearer " + admin)));
        assertEquals(7, result.get("total").asInt());
        result.get("list").forEach(ticket -> {
            assertTrue(ticket.get("overdue").asBoolean());
            assertFalse(List.of("CLOSED", "REJECTED", "CANCELLED").contains(ticket.get("status").asText()));
            assertNotEquals("KPI-NO-DUE", ticket.get("no").asText());
            assertNotEquals("IT-TEST-0001", ticket.get("no").asText());
        });
        JsonNode overview = body(mvc.perform(get("/stats/overview").header("Authorization", "Bearer " + admin)));
        assertEquals(overview.get("overdueCount").asInt(), result.get("total").asInt());
        JsonNode searched = body(mvc.perform(get("/tickets").param("status", "OVERDUE").param("keyword", "近一分钟")
                .header("Authorization", "Bearer " + admin)));
        assertEquals(1, searched.get("total").asInt());
        assertEquals("KPI-RECENT", searched.get("list").get(0).get("no").asText());
    }

    @Test void dashboardFiltersPreserveUserVisibilityRestrictions() throws Exception {
        seedDashboardTickets();
        String owner = login("普通用户乙"), viewer = login("赵敏");
        for (String filter : List.of("PROCESSING", "OVERDUE")) {
            JsonNode scoped = body(mvc.perform(get("/tickets").param("status", filter).header("Authorization", "Bearer " + owner)));
            assertEquals(1, scoped.get("total").asInt());
            assertEquals("KPI-PENDING_VENDOR", scoped.get("list").get(0).get("no").asText());
        }
        assertEquals(5, body(mvc.perform(get("/tickets").param("status", "PROCESSING")
                .header("Authorization", "Bearer " + viewer))).get("total").asInt());
        assertEquals(6, body(mvc.perform(get("/tickets").param("status", "OVERDUE")
                .header("Authorization", "Bearer " + viewer))).get("total").asInt());
    }

    @Test void sessionRestoresAndDatabaseRolesApplyImmediately() throws Exception {
        String token=login("陈静");
        body(mvc.perform(get("/auth/me").header("Authorization","Bearer "+token))).get("roles").forEach(r -> assertNotNull(r.asText()));
        db.update("update sys_user set role='USER' where id=3");
        mvc.perform(get("/admin/manage/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        db.update("update sys_user set status='DISABLED' where id=3");
        mvc.perform(get("/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/auth/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test void attachmentsEnforceOwnerOnListUploadRawAndDelete() throws Exception {
        String owner=login("张伟"), other=login("普通用户乙"), admin=login("陈静");
        JsonNode attachment=upload(owner); String stored=attachment.get("storedName").asText();
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/attachments").param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(delete("/attachments/"+attachment.get("id").asLong()).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        props.getDingtalk().setEnabled(true);
        mvc.perform(get("/attachments/raw/"+stored)).andExpect(status().isUnauthorized());
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+admin)).andExpect(status().isUnauthorized());
        String realAdmin = new com.tzme.helpdesk.security.JwtUtil(props).create(new LoginUser(3L,"陈静","ADMIN","技术中心"));
        mvc.perform(get("/attachments/raw/"+stored).header("Authorization","Bearer "+realAdmin)).andExpect(status().isOk());
    }

    @Test void attachmentsRejectUnknownBusinessTraversalAndExcessCount() throws Exception {
        String owner=login("张伟");
        for(int i=0;i<3;i++) upload(owner);
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-MISSING").header("Authorization","Bearer "+owner)).andExpect(status().isNotFound());
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","../outside").header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());
        assertEquals(3,db.queryForObject("select count(*) from it_attachment",Integer.class));
    }

    @Test void sequentialApprovalsCannotReallocateAssetOrApproveTwice() throws Exception {
        String admin=login("陈静");
        mvc.perform(post("/asset-requests/1/approve").contentType("application/json").content("{}").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        mvc.perform(post("/asset-requests/2/approve").contentType("application/json").content("{}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/asset-requests/1/approve").contentType("application/json").content("{}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals(1,db.queryForObject("select count(*) from it_asset_history",Integer.class));
    }

    @Test void concurrentApprovalsHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready=new CountDownLatch(2), go=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(long id:List.of(1L,2L)) results.add(pool.submit(() -> {
                CurrentUser.set(new LoginUser(3L,"陈静","ADMIN","技术中心"));
                ready.countDown(); go.await();
                try { requests.approve(id,new ApproveReq()); return true; }
                catch(BizException expected) { return false; }
                finally { CurrentUser.clear(); }
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
            int successes=0; for(Future<Boolean> r:results) if(r.get(15,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);
            assertEquals(1,db.queryForObject("select count(*) from it_asset_request where status='COMPLETED'",Integer.class));
            assertEquals(1,db.queryForObject("select count(*) from it_asset_history",Integer.class));
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test void returnsClearDueDateAndReplacementRechecksOwnership() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set status='借出中',borrower='张伟',borrow_due=current_date where no='ASSET-TEST-1'");
        db.update("update it_asset_request set type='RETURN' where id=1");
        mvc.perform(post("/asset-requests/1/approve").contentType("application/json").content("{}").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        assertNull(db.queryForObject("select borrow_due from it_asset where no='ASSET-TEST-1'",java.sql.Date.class));
        assertEquals("闲置",db.queryForObject("select status from it_asset where no='ASSET-TEST-1'",String.class));
        db.update("update it_asset set status='在用',user_name='赵敏' where no='ASSET-TEST-1'");
        db.update("update it_asset_request set type='REPLACE' where id=2");
        mvc.perform(post("/asset-requests/2/approve").contentType("application/json").content("{\"newAssetNo\":\"ASSET-TEST-1\"}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        db.update("update it_asset set user_name='张伟' where no='ASSET-TEST-1'");
        mvc.perform(post("/asset-requests/2/approve").contentType("application/json").content("{\"newAssetNo\":\"ASSET-TEST-2\"}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals("闲置",db.queryForObject("select status from it_asset where no='ASSET-TEST-2'",String.class));
    }

    @Test void settingsPersistValidateRevisionAndChangeTicketSla() throws Exception {
        String admin=login("陈静"), employee=login("张伟");
        ObjectNode s=(ObjectNode) settings(admin); s.put("p1Hours",2); s.put("maxAttachments",1);
        JsonNode saved=body(mvc.perform(put("/admin/manage/settings").contentType("application/json").content(s.toString()).header("Authorization","Bearer "+admin)));
        assertEquals(1,saved.get("revision").asLong());
        assertEquals(2,settings(admin).get("p1Hours").asInt());
        mvc.perform(put("/admin/manage/settings").contentType("application/json").content(s.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        mvc.perform(put("/admin/manage/settings").contentType("application/json").content(saved.toString()).header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        ObjectNode invalid=saved.deepCopy(); invalid.put("p1Hours",-1);
        mvc.perform(put("/admin/manage/settings").contentType("application/json").content(invalid.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        JsonNode t=body(mvc.perform(post("/tickets").contentType("application/json").content("{\"title\":\"安全事件测试\",\"description\":\"回归测试信息安全\",\"location\":\"A座3楼测试区\",\"cat1\":\"信息安全\",\"impact\":\"SELF\",\"workImpact\":\"SLOW\"}").header("Authorization","Bearer "+employee)));
        assertEquals("P1",t.get("priority").asText());
        assertEquals(120,db.queryForObject("select timestampdiff(minute,created_at,due_at) from it_ticket where no=?",Integer.class,t.get("no").asText()));
        upload(employee);
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+employee)).andExpect(status().isBadRequest());
    }

    @Test void dutySettingsAreEditableAndSurviveAnUpdateFromAnOlderClient() throws Exception {
        String admin = login("陈静"), employee = login("张伟");
        String settingsUrl = "/admin/manage/settings";
        db.update("insert into sys_setting(setting_key,setting_value) values('runtime',?)", "{\"revision\":3}");
        ObjectNode initial = (ObjectNode) settings(admin);
        assertEquals("8300", initial.path("duty").path("hotline").asText());
        assertEquals(3, initial.path("duty").path("members").size());

        ObjectNode duty = initial.putObject("duty").put("hotline","8123").put("hotlineNote","请联系当班人员");
        duty.putArray("members").addObject().put("position","网络").put("name","李强").put("userId",4);
        mvc.perform(put(settingsUrl).contentType("application/json").content(initial.toString())
                .header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        ObjectNode invalid = initial.deepCopy();
        ((ObjectNode) invalid.path("duty").path("members").get(0)).put("name"," ");
        mvc.perform(put(settingsUrl).contentType("application/json").content(invalid.toString())
                .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        JsonNode saved = body(mvc.perform(put(settingsUrl).contentType("application/json").content(initial.toString())
                .header("Authorization","Bearer "+admin)));
        assertEquals(4, saved.path("revision").asInt());
        assertEquals(duty, settings(admin).path("duty"));
        assertEquals(duty, body(mvc.perform(get("/meta/runtime").header("Authorization","Bearer "+admin))).path("duty"));
        JsonNode employeeDuty = body(mvc.perform(get("/meta/runtime").header("Authorization","Bearer "+employee))).path("duty");
        assertEquals("8123", employeeDuty.path("hotline").asText());
        assertEquals("请联系当班人员", employeeDuty.path("hotlineNote").asText());
        assertTrue(employeeDuty.path("members").isEmpty());
        mvc.perform(get("/meta/service-contacts").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        mvc.perform(get("/meta/service-contacts/people").param("keyword","李").header("Authorization","Bearer "+employee))
                .andExpect(status().isForbidden());
        var roster = body(mvc.perform(get("/meta/service-contacts").header("Authorization","Bearer "+admin)));
        assertEquals("李强", roster.path("contacts").get(0).path("member").path("name").asText());
        assertEquals(4L, roster.path("contacts").get(0).path("member").path("userId").asLong());
        assertEquals("UNLINKED", roster.path("contacts").get(0).path("status").asText());

        var display = body(mvc.perform(get("/meta/service-contacts/display")
                .header("Authorization","Bearer "+employee)).andExpect(status().isOk()));
        assertEquals("8123", display.path("hotline").asText());
        assertEquals("请联系当班人员", display.path("hotlineNote").asText());
        var contact = display.path("contacts").get(0);
        assertEquals("李强", contact.path("member").path("name").asText());
        assertEquals("网络", contact.path("member").path("position").asText());
        assertFalse(contact.path("member").hasNonNull("userId"));
        assertEquals("UNLINKED", contact.path("status").asText());

        ObjectNode olderPayload = saved.deepCopy();
        olderPayload.remove("duty");
        olderPayload.put("maxAttachments", 5);
        JsonNode updated = body(mvc.perform(put(settingsUrl).contentType("application/json").content(olderPayload.toString())
                .header("Authorization","Bearer "+admin)));
        assertEquals(5, updated.path("revision").asInt());
        assertEquals(duty, updated.path("duty"));
    }

    @Test void usersDepartmentsAndAuditAreRealAndProtected() throws Exception {
        String admin=login("陈静"), employee=login("张伟");
        mvc.perform(get("/admin/manage/users").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        assertEquals(6,body(mvc.perform(get("/admin/manage/users").header("Authorization","Bearer "+admin))).get("total").asInt());
        String selfDisable="{\"role\":\"ADMIN\",\"status\":\"DISABLED\",\"deptId\":1}";
        mvc.perform(put("/admin/manage/users/3").contentType("application/json").content(selfDisable).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/manage/users/4").contentType("application/json").content("{\"role\":\"USER\",\"status\":\"DISABLED\",\"deptId\":1}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/manage/users/1").contentType("application/json").content("{\"role\":\"IT\",\"status\":\"ACTIVE\",\"deptId\":2}").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        assertTrue(body(mvc.perform(get("/auth/me").header("Authorization","Bearer "+employee))).get("roles").toString().contains("IT"));
        mvc.perform(put("/admin/manage/departments/1").contentType("application/json").content("{\"name\":\"技术中心\",\"parentId\":2,\"sort\":0}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        JsonNode dept=body(mvc.perform(post("/admin/manage/departments").contentType("application/json").content("{\"name\":\"新部门\",\"parentId\":0,\"sort\":1}").header("Authorization","Bearer "+admin)));
        mvc.perform(delete("/admin/manage/departments/"+dept.get("id").asLong()).header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        assertTrue(body(mvc.perform(get("/admin/manage/audit").header("Authorization","Bearer "+admin))).get("total").asInt()>=3);
    }

    @Test void solutionDictionaryPersistsAndProtectsHistoricalCodes() throws Exception {
        String admin=login("陈静"), engineer=login("李强");
        JsonNode code=body(mvc.perform(post("/admin/manage/solution-codes").contentType("application/json").content("{\"category\":\"终端\",\"code\":\"TEST\",\"name\":\"回归测试代码\",\"sort\":2}").header("Authorization","Bearer "+admin)));
        db.update("update it_ticket set solution_code='TEST' where no='IT-TEST-0001'");
        mvc.perform(delete("/admin/manage/solution-codes/"+code.get("id").asLong()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals("TEST",db.queryForObject("select solution_code from it_ticket where no='IT-TEST-0001'",String.class));
    }

    @Test void latestFinishContractRequiresCategoryAndPersistsConfirmedClassification() throws Exception {
        String engineer=login("李强");
        mvc.perform(post("/tickets/IT-TEST-0001/finish").contentType("application/json").content("{\"code\":\"RESTART\",\"note\":\"旧请求缺少分类\"}")
                .header("Authorization","Bearer "+engineer)).andExpect(status().isBadRequest());
        JsonNode finished=body(mvc.perform(post("/tickets/IT-TEST-0001/finish").contentType("application/json")
                .content("{\"cat1\":\"终端\",\"cat2\":\"无法开机\",\"note\":\"排查电源并恢复正常\"}").header("Authorization","Bearer "+engineer)));
        assertEquals("PENDING_CONFIRM",finished.path("status").asText());
        assertEquals("终端",finished.path("cat1").asText());
        assertEquals("无法开机",finished.path("cat2").asText());
    }

    @Test void dingApprovalEndpointsWorkOnUpgradedSchemaAndRemainDisabledByDefault() throws Exception {
        String admin=login("陈静"),employee=login("张伟");
        JsonNode status=body(mvc.perform(get("/admin/ding-approval/status").header("Authorization","Bearer "+admin)));
        assertFalse(status.path("enabled").asBoolean()); assertFalse(status.path("mock").asBoolean());
        assertEquals(0,status.path("pending").asInt());
        mvc.perform(get("/admin/ding-approval/status").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/ding-approval/mock/AS-TEST-1").header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        assertEquals("PENDING",db.queryForObject("select status from it_asset_request where no='AS-TEST-1'",String.class));
        JsonNode fields=body(mvc.perform(get("/admin/ding-approval/fields").header("Authorization","Bearer "+admin)));
        assertEquals(10,fields.size());
        assertTrue(fields.toString().contains("newAssetNo"));
        mvc.perform(get("/admin/ding-approval/fields").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ding-approval/lookup").param("name","测试模板").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ding-approval/lookup").param("name","测试模板").header("Authorization","Bearer "+admin)).andExpect(status().isServiceUnavailable());
    }

    @Test void purchaseTablesSupportCatalogRequestAndDeliveryWithOwnerAccess() throws Exception {
        String owner=login("张伟"), other=login("普通用户乙"), admin=login("陈静");
        long catalogId=body(mvc.perform(post("/purchase-catalog").contentType("application/json")
                .content("{\"name\":\"部署检查设备\",\"category\":\"终端\"}").header("Authorization","Bearer "+admin))).path("id").asLong();
        assertEquals(1,body(mvc.perform(get("/purchase-catalog").header("Authorization","Bearer "+owner))).size());
        JsonNode created=body(mvc.perform(post("/purchase-requests").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("reason","申请配发测试设备","items",List.of(Map.of("catalogId",catalogId,"qty",1)))))
                .header("Authorization","Bearer "+owner)));
        String request="/purchase-requests/"+created.path("id").asLong();
        long item=created.path("items").get(0).path("id").asLong();
        mvc.perform(get(request).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        assertEquals("APPROVED",body(mvc.perform(post(request+"/approve").header("Authorization","Bearer "+admin))).path("status").asText());
        String itemPath=request+"/items/"+item;
        body(mvc.perform(post(itemPath+"/assign").contentType("application/json").content("{\"assetNo\":\"ASSET-TEST-2\"}")
                .header("Authorization","Bearer "+admin)));
        assertEquals("COMPLETED",body(mvc.perform(post(itemPath+"/deliver").header("Authorization","Bearer "+admin))).path("status").asText());
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-2'",String.class));
    }

    @Test void replacementHandoverPreservesTheAssetSelectedDuringApproval() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set status='在用',user_name='张伟' where no='ASSET-TEST-1'");
        db.update("update it_asset_request set type='REPLACE' where id=1");
        db.update("insert into sys_setting(setting_key,setting_value) values('runtime',?)", "{\"handoverCategories\":{\"REPLACE\":[\"终端\"]}}");
        JsonNode approved=body(mvc.perform(post("/asset-requests/1/approve").contentType("application/json")
                .content("{\"newAssetNo\":\"ASSET-TEST-2\"}").header("Authorization","Bearer "+admin)));
        assertEquals("RETURN",approved.path("handoverStatus").asText());
        assertEquals("ASSET-TEST-2",approved.path("newAssetNo").asText());
        JsonNode returned=body(mvc.perform(post("/asset-requests/1/handover").header("Authorization","Bearer "+admin)));
        assertEquals("PICKUP",returned.path("handoverStatus").asText());
        assertEquals("COMPLETED",body(mvc.perform(post("/asset-requests/1/handover").header("Authorization","Bearer "+admin))).path("status").asText());
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-2'",String.class));
    }

    @Test void presetReplacementSurvivesSubmissionAndDingApprovalSynchronization() throws Exception {
        String owner=login("张伟"),admin=login("陈静");
        db.update("delete from it_asset_request");
        db.update("update it_asset set status='在用',user_name='张伟' where no='ASSET-TEST-1'");
        db.update("insert into sys_setting(setting_key,setting_value) values('runtime',?)",
                "{\"approvalEnabled\":true,\"approvalMock\":true,\"approvalProcessCodes\":{\"REPLACE\":\"MOCK-TEST\"}}");
        JsonNode created=body(mvc.perform(post("/asset-requests").contentType("application/json")
                .content("{\"type\":\"REPLACE\",\"assetNo\":\"ASSET-TEST-1\",\"newAssetNo\":\"ASSET-TEST-2\",\"reason\":\"预选替换设备并模拟审批回写\"}")
                .header("Authorization","Bearer "+owner)));
        assertEquals("ASSET-TEST-2",created.path("newAssetNo").asText());
        assertTrue(created.path("dingInstanceId").asText().startsWith("MOCK-"));
        body(mvc.perform(post("/admin/ding-approval/mock/"+created.path("no").asText()).header("Authorization","Bearer "+admin)));
        assertEquals(1,body(mvc.perform(post("/admin/ding-approval/sync").header("Authorization","Bearer "+admin))).path("handled").asInt());
        assertEquals("COMPLETED",db.queryForObject("select status from it_asset_request where id=?",String.class,created.path("id").asLong()));
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-2'",String.class));
        assertEquals("闲置",db.queryForObject("select status from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals(0,body(mvc.perform(post("/admin/ding-approval/sync").header("Authorization","Bearer "+admin))).path("handled").asInt());
    }

    @Test void statisticsRailLoadsAllQueriesAndStillRejectsEmployees() throws Exception {
        String admin=login("陈静"), employee=login("张伟");
        for(String endpoint:List.of("overview","category","engineers","trend","problem-assets")) {
            body(mvc.perform(get("/stats/"+endpoint).header("Authorization","Bearer "+admin)));
            mvc.perform(get("/stats/"+endpoint).header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        }
    }

    ObjectNode policy(String token) throws Exception {
        JsonNode data=body(mvc.perform(get("/admin/manage/permissions").header("Authorization","Bearer "+token)));
        ObjectNode result=json.createObjectNode(); result.set("revision",data.get("revision")); result.set("selfServiceVersion",data.get("selfServiceVersion")); result.set("assignments",data.get("assignments")); return result;
    }
    void removePermission(ObjectNode policy,String role,String permission) {
        ArrayNode items=(ArrayNode)policy.get("assignments").get(role);
        for(int i=items.size()-1;i>=0;i--) if(permission.equals(items.get(i).asText())) items.remove(i);
    }
    JsonNode savePolicy(String token,ObjectNode value) throws Exception {
        return body(mvc.perform(put("/admin/manage/permissions").contentType("application/json").content(value.toString()).header("Authorization","Bearer "+token)));
    }

    @Test void exactlyFourSingleRolesAreExposedAndOldMultiRolePayloadIsRejected() throws Exception {
        String admin=login("陈静");
        JsonNode data=body(mvc.perform(get("/admin/manage/permissions").header("Authorization","Bearer "+admin)));
        assertEquals("[\"USER\",\"IT\",\"ADMIN\",\"VIEWER\"]",data.get("roles").toString());
        assertEquals("普通用户",data.get("roleNames").get("USER").asText());
        assertEquals("可查看用户",data.get("roleNames").get("VIEWER").asText());
        mvc.perform(put("/admin/manage/users/1").contentType("application/json").content("{\"roles\":[\"USER\",\"IT\"],\"status\":\"ACTIVE\",\"deptId\":1}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/manage/users/1").contentType("application/json").content("{\"role\":\"ENGINEER\",\"status\":\"ACTIVE\",\"deptId\":1}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }

    @Test void everyRoleCanSubmitPersonalTicketsAndAllFourAssetRequests() throws Exception {
        db.update("delete from it_asset_request");
        for (String name : List.of("张伟", "李强", "陈静", "赵敏")) {
            String token = login(name);
            JsonNode profile = body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)));
            for (String permission : com.tzme.helpdesk.security.Permissions.SELF_SERVICE)
                assertTrue(profile.path("permissions").toString().contains("\"" + permission + "\""), name + " missing " + permission);
            var payload = Map.of("title", name + "本人报修", "description", "测试每个角色都能提交本人的问题", "location", "A座3楼测试区", "cat1", "其他", "cat2", "其他");
            JsonNode ticket = body(mvc.perform(post("/tickets").contentType("application/json").content(json.writeValueAsBytes(payload)).header("Authorization", "Bearer " + token)));
            assertEquals(profile.path("id").asLong(), ticket.path("creatorId").asLong());
            String no = ticket.path("no").asText();
            JsonNode attachment = body(mvc.perform(multipart("/attachments").file(file()).param("bizType", "TICKET").param("bizNo", no).header("Authorization", "Bearer " + token)));
            mvc.perform(delete("/attachments/" + attachment.path("id").asLong()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            JsonNode mine = body(mvc.perform(get("/tickets").param("mine", "true").header("Authorization", "Bearer " + token)));
            mine.path("list").forEach(row -> assertEquals(profile.path("id").asLong(), row.path("creatorId").asLong()));
            body(mvc.perform(post("/tickets/" + no + "/cancel").header("Authorization", "Bearer " + token)));
            String owned = "OWN-" + profile.path("id").asLong(), borrowed = "BORROWED-" + profile.path("id").asLong();
            db.update("insert into it_asset(no,name,category,status,user_name) values(?,?,'终端','在用',?)", owned, "本人设备", name);
            db.update("insert into it_asset(no,name,category,status,borrower) values(?,?,'终端','借出中',?)", borrowed, "本人借用设备", name);
            for (String type : List.of("RECEIVE", "BORROW", "REPLACE", "RETURN")) {
                var application = new LinkedHashMap<String,Object>(Map.of("type", type, "assetNo", switch(type) { case "REPLACE" -> owned; case "RETURN" -> borrowed; default -> "ASSET-TEST-1"; }, "category", "终端", "reason", "全角色本人资产服务测试"));
                if (type.equals("BORROW")) application.put("expectReturn", java.time.LocalDate.now().plusDays(3).toString());
                JsonNode request = body(mvc.perform(post("/asset-requests").contentType("application/json").content(json.writeValueAsBytes(application)).header("Authorization", "Bearer " + token)));
                assertEquals(name, request.path("applicant").asText());
                assertEquals("PENDING", request.path("status").asText());
                body(mvc.perform(post("/asset-requests/" + request.path("id").asLong() + "/cancel").header("Authorization", "Bearer " + token)));
            }
            body(mvc.perform(get("/asset-requests").param("mine", "true").header("Authorization", "Bearer " + token))).forEach(row -> assertEquals(name, row.path("applicant").asText()));
        }
    }

    @Test void legacyRoleSettingsInheritSelfServiceWithoutResettingAdministrativeChoices() throws Exception {
        String admin = login("陈静"), it = login("李强"), viewer = login("赵敏");
        ObjectNode legacy = policy(admin); legacy.remove("selfServiceVersion");
        for (String role : List.of("USER", "IT", "ADMIN", "VIEWER"))
            for (String permission : com.tzme.helpdesk.security.Permissions.SELF_SERVICE) removePermission(legacy, role, permission);
        removePermission(legacy, "IT", "ticket.dispatch");
        db.update("insert into sys_setting(setting_key,setting_value) values('role-permissions',?)", legacy.toString());
        for (String token : List.of(admin, it, viewer)) {
            JsonNode me = body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)));
            assertTrue(me.path("permissions").toString().contains("request.create"));
            assertTrue(me.path("permissions").toString().contains("ticket.create"));
        }
        assertFalse(body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + it))).path("permissions").toString().contains("ticket.dispatch"));
        assertEquals(legacy.toString(), db.queryForObject("select setting_value from sys_setting where setting_key='role-permissions'", String.class));
        ObjectNode updated = policy(admin);
        assertEquals(1, updated.path("selfServiceVersion").asInt());
        savePolicy(admin, updated);
        assertEquals(1, json.readTree(db.queryForObject("select setting_value from sys_setting where setting_key='role-permissions'", String.class)).path("selfServiceVersion").asInt());
    }

    @Test void viewerCanReadOtherBusinessDataButCannotManageIt() throws Exception {
        String viewer=login("赵敏");
        JsonNode detail=body(mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization","Bearer "+viewer)));
        assertTrue(detail.get("actions").isEmpty());
        for(String path:List.of("/tickets","/assets","/asset-requests","/kb","/stats/overview")) body(mvc.perform(get(path).header("Authorization","Bearer "+viewer)));
        for(String path:List.of("/assets","/asset-requests/1/approve","/tickets/IT-TEST-0001/finish"))
            mvc.perform(post(path).contentType("application/json").content("{}").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
        mvc.perform(multipart("/attachments").file(file()).param("bizType","REQUEST").param("bizNo","AS-TEST-1").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/manage/permissions").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
    }

    @Test void permissionRevocationAndRestorationApplyToExistingJwt() throws Exception {
        String admin=login("陈静"), it=login("李强");
        db.update("update it_ticket set status='NEW' where no='IT-TEST-0001'");
        ObjectNode config=policy(admin); removePermission(config,"IT","ticket.accept");
        JsonNode saved=savePolicy(admin,config);
        mvc.perform(post("/tickets/IT-TEST-0001/accept").contentType("application/json").content("{}").header("Authorization","Bearer "+it)).andExpect(status().isForbidden());
        assertFalse(body(mvc.perform(get("/auth/me").header("Authorization","Bearer "+it))).get("permissions").toString().contains("ticket.accept"));
        ObjectNode restore=json.createObjectNode(); restore.set("revision",saved.get("revision")); restore.set("assignments",saved.get("assignments"));
        ((ArrayNode)restore.get("assignments").get("IT")).add("ticket.accept"); savePolicy(admin,restore);
        mvc.perform(post("/tickets/IT-TEST-0001/accept").contentType("application/json").content("{}").header("Authorization","Bearer "+it)).andExpect(status().isOk());
    }

    @Test void rolePolicyRejectsStaleWritesViewerMutationsAndAdministratorLockout() throws Exception {
        String admin=login("陈静"); ObjectNode original=policy(admin);
        ObjectNode viewerWrite=original.deepCopy(); ((ArrayNode)viewerWrite.get("assignments").get("VIEWER")).add("asset.create");
        mvc.perform(put("/admin/manage/permissions").contentType("application/json").content(viewerWrite.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        ObjectNode lockout=original.deepCopy(); removePermission(lockout,"ADMIN","role.manage");
        mvc.perform(put("/admin/manage/permissions").contentType("application/json").content(lockout.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        ObjectNode escalate=original.deepCopy(); ((ArrayNode)escalate.get("assignments").get("IT")).add("role.manage");
        mvc.perform(put("/admin/manage/permissions").contentType("application/json").content(escalate.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        savePolicy(admin,original);
        mvc.perform(put("/admin/manage/permissions").contentType("application/json").content(original.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        assertEquals(1,db.queryForObject("select count(*) from sys_audit_log where action='ROLE_PERMISSIONS_UPDATE'",Integer.class));
    }

    @Test void delegatedUserManagementCannotAssignAdministratorOrEditAdministrator() throws Exception {
        String admin=login("陈静"), it=login("李强"); ObjectNode config=policy(admin);
        ((ArrayNode)config.get("assignments").get("IT")).add("user.manage"); savePolicy(admin,config);
        body(mvc.perform(get("/admin/manage/users").header("Authorization","Bearer "+it)));
        mvc.perform(put("/admin/manage/users/1").contentType("application/json").content("{\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"deptId\":1}").header("Authorization","Bearer "+it)).andExpect(status().isForbidden());
        mvc.perform(put("/admin/manage/users/3").contentType("application/json").content("{\"role\":\"USER\",\"status\":\"ACTIVE\",\"deptId\":1}").header("Authorization","Bearer "+it)).andExpect(status().isForbidden());
    }

    @Test void viewerReadScopeIsAlsoConfigurableWithoutGrantingWriteAccess() throws Exception {
        String admin=login("陈静"), viewer=login("赵敏"); ObjectNode config=policy(admin);
        removePermission(config,"VIEWER","ticket.view.all"); savePolicy(admin,config);
        mvc.perform(get("/tickets/IT-TEST-0001").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
        assertEquals(0,body(mvc.perform(get("/tickets").header("Authorization","Bearer "+viewer))).get("total").asInt());
    }

    @Test void removingUploadPermissionPreventsWritesUsingExistingSession() throws Exception {
        String admin=login("陈静"), user=login("张伟"); ObjectNode config=policy(admin);
        removePermission(config,"USER","attachment.upload"); savePolicy(admin,config);
        mvc.perform(multipart("/attachments").file(file()).param("bizType","TICKET").param("bizNo","IT-TEST-0001").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
        assertEquals(0,db.queryForObject("select count(*) from it_attachment",Integer.class));
    }

    @Test void existingLegacyRolesMigrateWithoutChangingBusinessData() {
        db.update("update sys_user set role='DESK,ENGINEER' where id=4");
        db.update("update sys_user set role='IT_MANAGER,ASSET_ADMIN,ADMIN' where id=3");
        db.update("update sys_user set role='EMPLOYEE,DEPT_LEADER' where id=1");
        new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource).afterPropertiesSet();
        assertEquals("IT",db.queryForObject("select role from sys_user where id=4",String.class));
        assertEquals("ADMIN",db.queryForObject("select role from sys_user where id=3",String.class));
        assertEquals("USER",db.queryForObject("select role from sys_user where id=1",String.class));
        assertEquals("VIEWER",db.queryForObject("select role from sys_user where id=2",String.class));
        assertEquals(1,db.queryForObject("select count(*) from it_ticket",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from it_asset",Integer.class));
    }

    JsonNode createAssetCategory(String token,String name) throws Exception {
        return body(mvc.perform(post("/asset-categories").contentType("application/json").content(json.writeValueAsString(Map.of("name",name,"sort",10))).header("Authorization","Bearer "+token)));
    }

    @Test void assetCategoriesCanBeCreatedAssignedRenamedAndDeletedSafely() throws Exception {
        String admin=login("陈静"); JsonNode category=createAssetCategory(admin,"显示器"); long id=category.get("id").asLong();
        String displayNo=body(mvc.perform(post("/assets").contentType("application/json")
                .content("{\"erpNo\":\"DISPLAY-1\",\"name\":\"测试显示器\",\"category\":\"显示器\"}")
                .header("Authorization","Bearer "+admin))).path("no").asText();
        body(mvc.perform(put("/assets/ASSET-TEST-1/category").contentType("application/json").content("{\"category\":\"显示器\"}").header("Authorization","Bearer "+admin)));
        assertEquals(2,body(mvc.perform(get("/assets").param("category","显示器").header("Authorization","Bearer "+admin))).size());
        JsonNode updated=body(mvc.perform(put("/asset-categories/"+id).contentType("application/json").content("{\"name\":\"显示设备\",\"sort\":3}").header("Authorization","Bearer "+admin)));
        assertEquals(2,updated.get("assetCount").asInt());
        assertEquals(0,body(mvc.perform(get("/assets").param("category","显示器").header("Authorization","Bearer "+admin))).size());
        assertEquals(2,body(mvc.perform(get("/assets").param("category","显示设备").header("Authorization","Bearer "+admin))).size());
        mvc.perform(delete("/asset-categories/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        for(String no:List.of("ASSET-TEST-1",displayNo)) body(mvc.perform(put("/assets/"+no+"/category").contentType("application/json").content("{\"category\":\"终端\"}").header("Authorization","Bearer "+admin)));
        mvc.perform(delete("/asset-categories/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        assertEquals(1,db.queryForObject("select count(*) from sys_audit_log where action='ASSET_CATEGORY_DELETE'",Integer.class));
    }

    @Test void categoryMaintenanceRejectsDuplicatesUnknownCategoriesAndUnauthorizedWrites() throws Exception {
        String admin=login("陈静"), user=login("张伟"), viewer=login("赵敏");
        for(String token:List.of(user,viewer)) {
            body(mvc.perform(get("/asset-categories").header("Authorization","Bearer "+token)));
            mvc.perform(post("/asset-categories").contentType("application/json").content("{\"name\":\"非法分类\",\"sort\":0}").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(put("/assets/ASSET-TEST-1/category").contentType("application/json").content("{\"category\":\"打印复印\"}").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/asset-categories").contentType("application/json").content("{\"name\":\" 终端 \",\"sort\":0}").header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        mvc.perform(post("/assets").contentType("application/json").content("{\"no\":\"BAD-CATEGORY\",\"name\":\"错误分类资产\",\"category\":\"不存在的分类\"}").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals(0,db.queryForObject("select count(*) from it_asset where no='BAD-CATEGORY'",Integer.class));
    }

    @Test void allFourRequestsAcceptChosenCategoriesAndRejectStaleOrForeignAssets() throws Exception {
        db.update("delete from it_asset_request");
        String user=login("张伟");
        db.update("insert into it_asset(no,name,category,status,user_name) values('REPLACE-ME','本人旧设备','打印复印','在用','张伟')");
        db.update("insert into it_asset(no,name,category,status,borrower) values('RETURN-ME','本人借用设备','打印复印','借出中','张伟')");
        for(String[] item:List.of(new String[]{"RECEIVE","ASSET-TEST-1","终端"},new String[]{"BORROW","ASSET-TEST-2","终端"},new String[]{"REPLACE","REPLACE-ME","打印复印"},new String[]{"RETURN","RETURN-ME","打印复印"})) {
            Map<String,Object> request=new HashMap<>(Map.of("type",item[0],"assetNo",item[1],"category",item[2],"reason","分类申请回归测试"));
            if("BORROW".equals(item[0])) request.put("expectReturn",java.time.LocalDate.now().plusDays(1).toString());
            JsonNode result=body(mvc.perform(post("/asset-requests").contentType("application/json").content(json.writeValueAsString(request)).header("Authorization","Bearer "+user)));
            assertEquals(item[2],result.get("assetCategory").asText());
        }
        mvc.perform(post("/asset-requests").contentType("application/json").content("{\"type\":\"RECEIVE\",\"assetNo\":\"ASSET-TEST-1\",\"category\":\"打印复印\",\"reason\":\"不匹配的分类\"}").header("Authorization","Bearer "+user)).andExpect(status().isConflict());
        db.update("update it_asset set user_name='普通用户乙' where no='REPLACE-ME'");
        mvc.perform(post("/asset-requests").contentType("application/json").content("{\"type\":\"REPLACE\",\"assetNo\":\"REPLACE-ME\",\"category\":\"打印复印\",\"reason\":\"不能更换他人设备\"}").header("Authorization","Bearer "+user)).andExpect(status().isBadRequest());
    }

    @Test void replacementApprovalChecksSelectedNewCategoryAndPreservesAllocationOnFailure() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set status='在用',user_name='张伟' where no='ASSET-TEST-1'");
        db.update("update it_asset set category='打印复印' where no='ASSET-TEST-2'");
        db.update("update it_asset_request set type='REPLACE' where id=1");
        mvc.perform(post("/asset-requests/1/approve").contentType("application/json").content("{\"newAssetNo\":\"ASSET-TEST-2\",\"newAssetCategory\":\"终端\"}").header("Authorization","Bearer "+admin)).andExpect(status().isConflict());
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals("闲置",db.queryForObject("select status from it_asset where no='ASSET-TEST-2'",String.class));
        body(mvc.perform(post("/asset-requests/1/approve").contentType("application/json").content("{\"newAssetNo\":\"ASSET-TEST-2\",\"newAssetCategory\":\"打印复印\"}").header("Authorization","Bearer "+admin)));
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-2'",String.class));
    }

    @Test void assigningCategoryPreservesBorrowStateAndDates() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set status='借出中',borrower='张伟',borrow_due=? where no='ASSET-TEST-1'",java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(3)));
        body(mvc.perform(put("/assets/ASSET-TEST-1/category").contentType("application/json").content("{\"category\":\"打印复印\"}").header("Authorization","Bearer "+admin)));
        assertEquals("借出中",db.queryForObject("select status from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals("张伟",db.queryForObject("select borrower from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals(java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(3)),db.queryForObject("select borrow_due from it_asset where no='ASSET-TEST-1'",java.sql.Date.class));
    }

    @Test void categoryUpgradeImportsHistoricalNamesWithoutRecreatingDeletedDefaults() {
        db.update("update it_asset set category='历史自定义分类' where no='ASSET-TEST-1'");
        db.update("delete from it_asset_category where name='服务器'");
        new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource).afterPropertiesSet();
        assertEquals(1,db.queryForObject("select count(*) from it_asset_category where name='历史自定义分类'",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from it_asset_category where name='服务器'",Integer.class));
        assertEquals("历史自定义分类",db.queryForObject("select category from it_asset where no='ASSET-TEST-1'",String.class));
    }

    ObjectNode assetEdit(String token,String no) throws Exception {
        JsonNode detail=body(mvc.perform(get("/assets/"+no).header("Authorization","Bearer "+token)));
        ObjectNode value=json.createObjectNode(); JsonNode asset=detail.get("asset");
        for(String key:List.of("name","model","category","sn","location","buyDate","warranty","warrantyEnd","status","remark")) {
            if(asset.has(key)) value.set(key,asset.get(key)); else value.putNull(key);
        }
        value.set("editToken",detail.get("editToken")); return value;
    }
    ResultActions putAsset(String token,String no,ObjectNode value) throws Exception {
        return mvc.perform(put("/assets/"+no).contentType("application/json").content(value.toString()).header("Authorization","Bearer "+token));
    }

    @Test void deviceDetailsAndEditingPersistFieldsWithHistoryAndAudit() throws Exception {
        String admin=login("陈静"); ObjectNode edit=assetEdit(admin,"ASSET-TEST-1");
        assertEquals(64,edit.get("editToken").asText().length());
        edit.put("name","办公终端更新"); edit.put("category","打印复印"); edit.put("sn","SN-UPDATED"); edit.put("location","A座三层");
        edit.put("buyDate","2025-01-01"); edit.put("warranty","厂商保修"); edit.put("warrantyEnd","2028-01-01"); edit.put("status","维修中"); edit.put("remark","已完成设备资料更新");
        JsonNode saved=body(putAsset(admin,"ASSET-TEST-1",edit));
        assertEquals("办公终端更新",saved.get("asset").get("name").asText());
        assertEquals("打印复印",saved.get("asset").get("category").asText());
        assertEquals("SN-UPDATED",saved.get("asset").get("sn").asText());
        assertEquals("维修中",saved.get("asset").get("status").asText());
        assertEquals("2025-01-01",db.queryForObject("select cast(buy_date as varchar) from it_asset where no='ASSET-TEST-1'",String.class));
        assertEquals("编辑设备",saved.get("history").get(0).get("action").asText());
        assertNotEquals(edit.get("editToken").asText(),saved.get("editToken").asText());
        assertEquals(1,db.queryForObject("select count(*) from sys_audit_log where action='ASSET_UPDATE'",Integer.class));
    }

    @Test void assetCreationSavesAllEditableFieldsAndModelAndSearchesByModel() throws Exception {
        String it=login("李强");
        var input=json.createObjectNode().put("no","  ASSET-FULL  ").put("name","  办公笔记本  ").put("model","  ThinkPad T14s Gen 5  ")
                .put("category","终端").put("sn"," SN-CREATE ").put("location"," A座3楼 ").put("buyDate","2026-01-01")
                .put("warranty"," 厂商保修 ").put("warrantyEnd","2029-01-01").put("status","闲置").put("remark"," 新增时填齐资料 ");
        JsonNode created=body(mvc.perform(post("/assets").contentType("application/json").content(input.toString()).header("Authorization","Bearer "+it)));
        String no=created.path("no").asText();
        assertTrue(no.matches("ZC-[0-9]{8}-[0-9]{5}"));
        assertEquals("ASSET-FULL",created.path("erpNo").asText());
        var expected=Map.of("name","办公笔记本","model","ThinkPad T14s Gen 5","category","终端","sn","SN-CREATE","location","A座3楼",
                "buyDate","2026-01-01","warranty","厂商保修","warrantyEnd","2029-01-01","status","闲置","remark","新增时填齐资料");
        JsonNode detail=body(mvc.perform(get("/assets/"+no).header("Authorization","Bearer "+it))).path("asset");
        expected.forEach((key,value) -> { assertEquals(value,created.path(key).asText(),key); assertEquals(value,detail.path(key).asText(),key); });
        assertEquals("ThinkPad T14s Gen 5",db.queryForObject("select model from it_asset where no=?",String.class,no));
        JsonNode page=body(mvc.perform(get("/assets").param("page","1").param("keyword","T14s Gen").header("Authorization","Bearer "+it)));
        assertEquals(1,page.path("total").asInt()); assertEquals(no,page.path("list").get(0).path("no").asText());
        assertEquals(1,body(mvc.perform(get("/assets").param("keyword","T14s Gen").header("Authorization","Bearer "+it))).size());
        assertEquals(1,db.queryForObject("select count(*) from it_asset_history where asset_no=? and action='入库'",Integer.class,no));
    }

    @Test void assetTemplateAndExcelImportUseCurrentCategoriesAndAssetCreationRules() throws Exception {
        String it = login("李强"), employee = login("张伟");
        mvc.perform(get("/assets/import/template").header("Authorization", "Bearer " + employee))
                .andExpect(status().isForbidden());
        try (var workbook = importTemplate(it)) {
            var sheet = workbook.getSheet("资产导入");
            assertEquals("ERP资产编号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("型号", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals(2, sheet.getDataValidations().size());
            assertTrue(workbook.getName("AssetCategories").getRefersToFormula().contains("填写说明"));
            assertEquals(0, sheet.getLastRowNum());
            var example = workbook.getSheet("填写示例");
            assertEquals(sheet.getRow(0).getLastCellNum(), example.getRow(0).getLastCellNum());
            assertEquals(1, example.getLastRowNum());
            assertEquals("ERP-DEMO-0001", example.getRow(1).getCell(0).getStringCellValue());
            assertEquals("终端", example.getRow(1).getCell(2).getStringCellValue());
            assertEquals("00012345", example.getRow(1).getCell(4).getStringCellValue());
            assertEquals(LocalDate.of(2026, 1, 10), example.getRow(1).getCell(6).getLocalDateTimeCellValue().toLocalDate());
            mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + it))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("请在模板第 2 行起填写要导入的资产"));
            Row full = importRow(workbook, 1, "00042", "终端");
            full.createCell(3).setCellValue("ThinkPad T14s");
            full.createCell(4).setCellValue("000088");
            full.createCell(5).setCellValue("A座三层库房");
            var date = full.createCell(6);
            date.setCellValue(LocalDate.of(2026, 1, 5));
            date.setCellStyle(sheet.getColumnStyle(6));
            full.createCell(7).setCellValue("厂商保修");
            full.createCell(8).setCellValue("2029-01-05");
            full.createCell(10).setCellValue("批量登记");
            full.createCell(11).setCellValue("laptop");
            sheet.createRow(2).createCell(0).setCellValue(" ");
            Row minimal = importRow(workbook, 3, null, "终端");
            minimal.getCell(1).setCellValue("未填ERP的设备");
            var upload = excelUpload(workbook);
            mvc.perform(multipart("/assets/import").file(upload).header("Authorization", "Bearer " + employee))
                    .andExpect(status().isForbidden());
            assertEquals(2, body(mvc.perform(multipart("/assets/import").file(upload)
                    .header("Authorization", "Bearer " + it))).path("imported").asInt());
        }
        String no=db.queryForObject("select no from it_asset where erp_no='00042'",String.class);
        assertTrue(no.matches("ZC-[0-9]{8}-[0-9]{5}"));
        var detail = body(mvc.perform(get("/assets/"+no).header("Authorization", "Bearer " + it)));
        var expected = Map.of("model", "ThinkPad T14s", "sn", "000088", "location", "A座三层库房",
                "buyDate", "2026-01-05", "warranty", "厂商保修", "warrantyEnd", "2029-01-05",
                "status", "闲置", "remark", "批量登记", "icon", "laptop");
        expected.forEach((key, value) -> assertEquals(value, detail.path("asset").path(key).asText(), key));
        assertEquals("00042",detail.path("asset").path("erpNo").asText());
        assertEquals("待入库", db.queryForObject("select status from it_asset where name='未填ERP的设备' and erp_no is null", String.class));
        assertEquals("入库", detail.path("history").get(0).path("action").asText());
        assertEquals(2, db.queryForObject("select count(*) from sys_audit_log where action='ASSET_CREATE'", Integer.class));
    }

    @Test void assetExcelImportReportsTheSheetRowAndRollsBackEarlierRows() throws Exception {
        String admin = login("陈静");
        db.update("update it_asset set erp_no='ASSET-TEST-1' where no='ASSET-TEST-1'");
        try (var workbook = importTemplate(admin)) {
            importRow(workbook, 1, "IMPORT-ROLLBACK", "终端");
            importRow(workbook, 3, "ASSET-TEST-1", "终端");
            mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("第 4 行：ERP资产编号已存在"));
            assertEquals(0, db.queryForObject("select count(*) from it_asset where erp_no='IMPORT-ROLLBACK'", Integer.class));
            assertEquals(0, db.queryForObject("select count(*) from it_asset_history", Integer.class));
            assertEquals(0, db.queryForObject("select count(*) from sys_audit_log where action='ASSET_CREATE'", Integer.class));
            var invalid = workbook.getSheetAt(0).getRow(3).createCell(6);
            invalid.setCellValue("2026-02-30");
            mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("第 4 行：购置日期请填写有效日期，格式为 YYYY-MM-DD"));
            invalid.setCellFormula("1+1");
            mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("第 4 行：购置日期请填写文本或数值，公式请先粘贴为值"));
        }
        mvc.perform(multipart("/assets/import").file(new MockMultipartFile("file", "broken.xlsx",
                        AssetWorkbook.CONTENT_TYPE, new byte[]{1, 2, 3})).header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test void assetExcelImportAssignsCurrentUsersAndAcceptsThePreviousTemplate() throws Exception {
        String it = login("李强"), holder = login("张伟"), other = login("普通用户乙");
        try (var workbook = importTemplate(it)) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertEquals("使用人", header.getCell(12).getStringCellValue());
            Row assigned = importRow(workbook, 1, "IMPORT-ASSIGNED", "终端");
            assigned.createCell(5).setCellValue("三楼");
            assigned.createCell(12).setCellValue(" 张伟 ");
            Row explicit = importRow(workbook, 2, "IMPORT-EXPLICIT", "终端");
            explicit.createCell(9).setCellValue("在用");
            explicit.createCell(12).setCellValue("李强");
            assertEquals(2, body(mvc.perform(multipart("/assets/import").file(excelUpload(workbook))
                    .header("Authorization", "Bearer " + it))).path("imported").asInt());
            workbook.getSheetAt(0).removeRow(assigned);
            workbook.getSheetAt(0).removeRow(explicit);
            header.removeCell(header.getCell(12));
            header.getCell(0).setCellValue("资产编号");
            importRow(workbook, 1, "IMPORT-PREVIOUS-TEMPLATE", "终端");
            assertEquals(1, body(mvc.perform(multipart("/assets/import").file(excelUpload(workbook))
                    .header("Authorization", "Bearer " + it))).path("imported").asInt());
        }
        String assignedNo = db.queryForObject("select no from it_asset where erp_no='IMPORT-ASSIGNED'", String.class);
        var detail = body(mvc.perform(get("/assets/" + assignedNo).header("Authorization", "Bearer " + holder)));
        assertEquals("张伟", detail.path("asset").path("userName").asText());
        assertEquals("在用", detail.path("asset").path("status").asText());
        assertTrue(detail.path("history").get(0).path("detail").asText().contains("使用人=张伟"));
        assertEquals("李强", db.queryForObject("select user_name from it_asset where erp_no='IMPORT-EXPLICIT'", String.class));
        assertEquals("待入库", db.queryForObject("select status from it_asset where erp_no='IMPORT-PREVIOUS-TEMPLATE'", String.class));
        assertNull(db.queryForObject("select user_name from it_asset where erp_no='IMPORT-PREVIOUS-TEMPLATE'", String.class));
        assertEquals(assignedNo, body(mvc.perform(get("/assets").param("keyword", "张伟")
                .header("Authorization", "Bearer " + it))).get(0).path("no").asText());
        String request = json.writeValueAsString(Map.of("type","RETURN","assetNo",assignedNo,"reason","导入设备归还"));
        mvc.perform(post("/asset-requests").contentType("application/json").content(request)
                .header("Authorization", "Bearer " + other)).andExpect(status().isBadRequest());
        assertEquals("张伟", body(mvc.perform(post("/asset-requests").contentType("application/json").content(request)
                .header("Authorization", "Bearer " + holder))).path("applicant").asText());
    }

    @Test void assetExcelImportRejectsUnknownOrAmbiguousUsersAndConflictingStatuses() throws Exception {
        String it = login("李强");
        db.update("insert into sys_user(id,name,role,status) values(99,'张伟','USER','ACTIVE'),(98,'已停用人员','USER','DISABLED')");
        try (var workbook = importTemplate(it)) {
            importRow(workbook, 1, "IMPORT-FIRST", "终端");
            Row row = importRow(workbook, 2, "IMPORT-INVALID-HOLDER", "终端");
            var holder = row.createCell(12);
            for (String name : List.of("不存在的人", "已停用人员", "张伟")) {
                holder.setCellValue(name);
                String error = name.equals("张伟") ? "通讯录中有多名“张伟”，无法确定使用人"
                        : "通讯录中找不到在职人员“" + name + "”，请核对使用人姓名";
                mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + it))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("第 3 行：" + error));
            }
            holder.setCellValue("李强");
            row.createCell(9).setCellValue("闲置");
            mvc.perform(multipart("/assets/import").file(excelUpload(workbook)).header("Authorization", "Bearer " + it))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("第 3 行：填写使用人时，设备状态请留空或填写在用"));
        }
        assertEquals(0, db.queryForObject("select count(*) from it_asset where erp_no like 'IMPORT-%'", Integer.class));
    }

    @Test void assetNumberGenerationContinuesAfterExistingNumbersAndHandlesConcurrentCreation() throws Exception {
        String admin = login("陈静");
        String day = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "ZC-" + day + "-";
        db.update("insert into it_asset(no,name,category) values(?, '原有资产', '终端')", prefix + "00007");
        db.update("insert into it_asset(no,name,category) values(?, '旧格式编号', '终端')", prefix + "MANUAL");
        String input = "{\"name\":\"自动编号测试\",\"category\":\"终端\"}";
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = Collections.nCopies(4, () -> body(mvc.perform(post("/assets")
                    .contentType("application/json").content(input).header("Authorization", "Bearer " + admin))).path("no").asText());
            var numbers = new HashSet<String>();
            for (var result : pool.invokeAll(tasks, 15, TimeUnit.SECONDS)) numbers.add(result.get());
            assertEquals(Set.of(prefix+"00008",prefix+"00009",prefix+"00010",prefix+"00011"),numbers);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void erpAssetNumberCanBeSearchedEditedAndClearedWhileSystemNumberAndQrStayStable() throws Exception {
        String admin = login("陈静");
        String no = "ASSET-TEST-1";
        String qrBefore = body(mvc.perform(get("/assets/"+no+"/qrcode").header("Authorization","Bearer "+admin))).path("content").asText();
        db.update("update it_asset set erp_no='ERP-USED' where no='ASSET-TEST-2'");
        ObjectNode edit = assetEdit(admin,no);
        edit.put("erpNo","ERP-USED");
        putAsset(admin,no,edit).andExpect(status().isConflict());
        edit.put("erpNo"," ERP-00017 ");
        JsonNode saved = body(putAsset(admin,no,edit));
        assertEquals("ERP-00017",saved.path("asset").path("erpNo").asText());
        assertEquals(no,body(mvc.perform(get("/assets").param("keyword","00017").param("page","1")
                .header("Authorization","Bearer "+admin))).path("list").get(0).path("no").asText());
        ObjectNode legacyEdit = assetEdit(admin,no);
        legacyEdit.put("name","兼容旧版编辑");
        assertEquals("ERP-00017",body(putAsset(admin,no,legacyEdit)).path("asset").path("erpNo").asText());
        ObjectNode clear = assetEdit(admin,no);
        clear.putNull("erpNo");
        assertEquals(no,body(putAsset(admin,no,clear)).path("asset").path("no").asText());
        assertNull(db.queryForObject("select erp_no from it_asset where no=?",String.class,no));
        assertEquals(qrBefore,body(mvc.perform(get("/assets/"+no+"/qrcode").header("Authorization","Bearer "+admin))).path("content").asText());
    }

    private XSSFWorkbook importTemplate(String token) throws Exception {
        byte[] bytes = mvc.perform(get("/assets/import/template").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(AssetWorkbook.CONTENT_TYPE))
                .andExpect(header().exists("Content-Disposition")).andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private Row importRow(XSSFWorkbook workbook, int index, String erpNo, String category) {
        Row row = workbook.getSheetAt(0).createRow(index);
        if (erpNo != null) row.createCell(0).setCellValue(erpNo);
        row.createCell(1).setCellValue("测试设备");
        row.createCell(2).setCellValue(category);
        return row;
    }

    private MockMultipartFile excelUpload(XSSFWorkbook workbook) throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return new MockMultipartFile("file", "设备.xlsx", AssetWorkbook.CONTENT_TYPE, output.toByteArray());
        }
    }

    @Test void assetsBecomeIdleOnlyWhenPendingStorageHasANonBlankLocation() throws Exception {
        String admin=login("陈静");
        for(String location:List.of("", "  ", "  11  ")) {
            ObjectNode input=json.createObjectNode().put("no","STORAGE-"+UUID.randomUUID().toString().substring(0,8))
                    .put("name","入库状态测试").put("category","终端").put("status","待入库").put("location",location);
            JsonNode created=body(mvc.perform(post("/assets").contentType("application/json").content(input.toString()).header("Authorization","Bearer "+admin)));
            assertEquals(location.isBlank()?"待入库":"闲置",created.path("status").asText());
            assertEquals(location.isBlank()?null:"11",db.queryForObject("select location from it_asset where no=?",String.class,created.path("no").asText()));
        }
        // 编辑只提交位置和原待入库状态时，服务端也会转换并记录状态变更。
        db.update("update it_asset set status='待入库',location=null where no='ASSET-TEST-1'");
        ObjectNode edit=assetEdit(admin,"ASSET-TEST-1"); edit.put("location","  11  ");
        JsonNode saved=body(putAsset(admin,"ASSET-TEST-1",edit));
        assertEquals("闲置",saved.path("asset").path("status").asText());
        assertEquals("11",saved.path("asset").path("location").asText());
        String audit=db.queryForObject("select detail from sys_audit_log where action='ASSET_UPDATE' and target='ASSET-TEST-1'",String.class);
        assertTrue(audit.contains("待入库 → 闲置"));
        assertTrue(saved.path("history").get(0).path("detail").asText().contains("状态"));
        ObjectNode cleared=assetEdit(admin,"ASSET-TEST-1"); cleared.put("location","");
        assertEquals("闲置",body(putAsset(admin,"ASSET-TEST-1",cleared)).path("asset").path("status").asText());
        // 已经填写位置的旧待入库设备在下次保存时同样生效。
        db.update("update it_asset set status='待入库',location='原有库位' where no='ASSET-TEST-1'");
        assertEquals("闲置",body(putAsset(admin,"ASSET-TEST-1",assetEdit(admin,"ASSET-TEST-1"))).path("asset").path("status").asText());
    }

    @Test void fillingLocationDoesNotOverrideOtherAssetStatesOrAllocation() throws Exception {
        String admin=login("陈静");
        for(String status:List.of("闲置","在用","维修中","待报废","已报废","借出中")) {
            String borrower="借出中".equals(status)?"张伟":null;
            String user="在用".equals(status)?"张伟":null;
            db.update("update it_asset set status=?,user_name=?,borrower=?,location=null where no='ASSET-TEST-1'",status,user,borrower);
            ObjectNode edit=assetEdit(admin,"ASSET-TEST-1"); edit.put("location","11");
            JsonNode saved=body(putAsset(admin,"ASSET-TEST-1",edit));
            assertEquals(status,saved.path("asset").path("status").asText());
            assertEquals(user,db.queryForObject("select user_name from it_asset where no='ASSET-TEST-1'",String.class));
            assertEquals(borrower,db.queryForObject("select borrower from it_asset where no='ASSET-TEST-1'",String.class));
        }
        db.update("update it_asset set status='待入库',borrower=null,user_name='张伟',location=null where no='ASSET-TEST-1'");
        ObjectNode inconsistent=assetEdit(admin,"ASSET-TEST-1"); inconsistent.put("location","11");
        putAsset(admin,"ASSET-TEST-1",inconsistent).andExpect(status().isConflict());
        assertEquals("张伟",db.queryForObject("select user_name from it_asset where no='ASSET-TEST-1'",String.class));
    }

    @Test void assetModelEditsCanClearValuesAndCannotOverwriteConcurrentModelChanges() throws Exception {
        String admin=login("陈静"); ObjectNode old=assetEdit(admin,"ASSET-TEST-1");
        ObjectNode updated=old.deepCopy(); updated.put("model","  T14s 2026  ");
        JsonNode saved=body(putAsset(admin,"ASSET-TEST-1",updated));
        assertEquals("T14s 2026",saved.path("asset").path("model").asText());
        assertNotEquals(old.path("editToken"),saved.path("editToken"));
        old.put("location","不应保存的位置"); putAsset(admin,"ASSET-TEST-1",old).andExpect(status().isConflict());
        body(mvc.perform(put("/assets/ASSET-TEST-1/category").contentType("application/json").content("{\"category\":\"打印复印\"}").header("Authorization","Bearer "+admin)));
        assertEquals("T14s 2026",db.queryForObject("select model from it_asset where no='ASSET-TEST-1'",String.class));
        assertTrue(db.queryForObject("select detail from sys_audit_log where action='ASSET_UPDATE'",String.class).contains("型号"));
        ObjectNode clear=assetEdit(admin,"ASSET-TEST-1"); clear.put("model","  "); body(putAsset(admin,"ASSET-TEST-1",clear));
        assertNull(db.queryForObject("select model from it_asset where no='ASSET-TEST-1'",String.class));
    }

    @Test void assetCreationValidatesTheSameDatesAndLengthsAsEditing() throws Exception {
        String admin=login("陈静");
        ObjectNode valid=json.createObjectNode().put("erpNo","VALIDATION-ASSET").put("name","验证资产").put("category","终端");
        for(var invalid:List.of(Map.of("model","字".repeat(129)),Map.of("sn","字".repeat(65)),Map.of("location","字".repeat(65)),
                Map.of("warranty","字".repeat(33)),Map.of("remark","字".repeat(256)),Map.of("erpNo","字".repeat(33)),Map.of("status","未知状态"),Map.of("status","借出中"),
                Map.of("buyDate","2027-01-01","warrantyEnd","2026-01-01"),Map.of("buyDate","not-a-date"))) {
            ObjectNode value=valid.deepCopy(); invalid.forEach(value::put);
            mvc.perform(post("/assets").contentType("application/json").content(value.toString()).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        }
        ObjectNode edit=assetEdit(admin,"ASSET-TEST-1"); edit.put("model","字".repeat(129)); putAsset(admin,"ASSET-TEST-1",edit).andExpect(status().isBadRequest());
        assertEquals(0,db.queryForObject("select count(*) from it_asset where erp_no='VALIDATION-ASSET'",Integer.class));
        valid.put("model","字".repeat(128));
        assertEquals(128,body(mvc.perform(post("/assets").contentType("application/json").content(valid.toString()).header("Authorization","Bearer "+admin))).path("model").asText().length());
    }

    @Test void assetOptionalFieldsCanBeOmittedAndAllocationMustUseTheApplicationFlow() throws Exception {
        String admin=login("陈静");
        var input=json.createObjectNode().put("name","最少资料").put("category","终端")
                .put("userName","不应直接分配").put("borrower","不应直接借出").put("borrowDue","2026-12-31");
        JsonNode minimal=body(mvc.perform(post("/assets").contentType("application/json").content(input.toString()).header("Authorization","Bearer "+admin)));
        assertEquals("待入库",minimal.path("status").asText());
        assertTrue(minimal.path("no").asText().matches("ZC-[0-9]{8}-[0-9]{5}"));
        for(String column:List.of("erp_no","model","sn","location","buy_date","warranty","warranty_end","remark","user_name","borrower","borrow_due"))
            assertNull(db.queryForObject("select "+column+" from it_asset where no=?",Object.class,minimal.path("no").asText()));
        for(String name:List.of("张伟","赵敏")) mvc.perform(post("/assets").contentType("application/json").content(input.toString())
                .header("Authorization","Bearer "+login(name))).andExpect(status().isForbidden());
    }

    @Test void deviceEditorCanClearOptionalDatesAndCannotRenameAssetNumberOrChangeAllocation() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set sn='OLD-SN',location='旧位置',buy_date='2024-01-01',warranty='厂商保修',warranty_end='2027-01-01',remark='旧备注',user_name='张伟',status='在用' where no='ASSET-TEST-1'");
        ObjectNode edit=assetEdit(admin,"ASSET-TEST-1");
        edit.put("sn",""); edit.put("location",""); edit.put("warranty",""); edit.put("remark",""); edit.putNull("buyDate"); edit.putNull("warrantyEnd");
        edit.put("no","RENAMED-INVALID"); edit.put("userName","赵敏"); edit.put("borrower","赵敏");
        JsonNode saved=body(putAsset(admin,"ASSET-TEST-1",edit));
        assertEquals("ASSET-TEST-1",saved.get("asset").get("no").asText());
        assertEquals("张伟",saved.get("asset").get("userName").asText());
        for(String column:List.of("sn","location","buy_date","warranty","warranty_end","remark"))
            assertNull(db.queryForObject("select "+column+" from it_asset where no='ASSET-TEST-1'",Object.class));
        assertEquals(0,db.queryForObject("select count(*) from it_asset where no='RENAMED-INVALID'",Integer.class));
    }

    @Test void staleDeviceEditsCannotOverwriteAnotherEditCategoryOrAllocation() throws Exception {
        String admin=login("陈静"); ObjectNode original=assetEdit(admin,"ASSET-TEST-1");
        ObjectNode first=original.deepCopy(); first.put("name","先保存的名称"); body(putAsset(admin,"ASSET-TEST-1",first));
        original.put("name","不应覆盖的旧编辑"); putAsset(admin,"ASSET-TEST-1",original).andExpect(status().isConflict());
        assertEquals("先保存的名称",db.queryForObject("select name from it_asset where no='ASSET-TEST-1'",String.class));
        ObjectNode beforeCategory=assetEdit(admin,"ASSET-TEST-1");
        body(mvc.perform(put("/assets/ASSET-TEST-1/category").contentType("application/json").content("{\"category\":\"打印复印\"}").header("Authorization","Bearer "+admin)));
        putAsset(admin,"ASSET-TEST-1",beforeCategory).andExpect(status().isConflict());
        ObjectNode beforeBorrow=assetEdit(admin,"ASSET-TEST-1");
        db.update("update it_asset set status='借出中',borrower='张伟',borrow_due=current_date where no='ASSET-TEST-1'");
        putAsset(admin,"ASSET-TEST-1",beforeBorrow).andExpect(status().isConflict());
        assertEquals("张伟",db.queryForObject("select borrower from it_asset where no='ASSET-TEST-1'",String.class));
    }

    @Test void editorProtectsInUseAndBorrowedAssetStatesWhileAllowingMetadataChanges() throws Exception {
        String admin=login("陈静");
        db.update("update it_asset set status='在用',user_name='张伟' where no='ASSET-TEST-1'");
        ObjectNode edit=assetEdit(admin,"ASSET-TEST-1"); edit.put("status","闲置");
        putAsset(admin,"ASSET-TEST-1",edit).andExpect(status().isConflict());
        edit.put("status","在用"); edit.put("location","新工位"); body(putAsset(admin,"ASSET-TEST-1",edit));
        db.update("update it_asset set status='借出中',borrower='张伟',borrow_due=? where no='ASSET-TEST-2'",java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(3)));
        ObjectNode borrowed=assetEdit(admin,"ASSET-TEST-2"); borrowed.put("name","借用设备资料更新");
        JsonNode saved=body(putAsset(admin,"ASSET-TEST-2",borrowed));
        assertEquals("借出中",saved.get("asset").get("status").asText()); assertEquals("张伟",saved.get("asset").get("borrower").asText());
        assertNotNull(saved.get("asset").get("borrowDue"));
        assertEquals("[\"借出中\"]",saved.get("editableStatuses").toString());
        ObjectNode unassigned=assetEdit(admin,"ASSET-TEST-1"); unassigned.put("status","借出中");
        putAsset(admin,"ASSET-TEST-1",unassigned).andExpect(status().isConflict());
    }

    @Test void deviceEditorRejectsInvalidDatesCategoryAndEmptyName() throws Exception {
        String admin=login("陈静"); ObjectNode edit=assetEdit(admin,"ASSET-TEST-1");
        edit.put("category","不存在的分类"); putAsset(admin,"ASSET-TEST-1",edit).andExpect(status().isBadRequest());
        edit.put("category","终端"); edit.put("buyDate","2026-01-01"); edit.put("warrantyEnd","2025-01-01"); putAsset(admin,"ASSET-TEST-1",edit).andExpect(status().isBadRequest());
        edit.putNull("warrantyEnd"); edit.put("name"," "); putAsset(admin,"ASSET-TEST-1",edit).andExpect(status().isBadRequest());
        assertEquals("测试笔记本",db.queryForObject("select name from it_asset where no='ASSET-TEST-1'",String.class));
    }

    @Test void deviceDetailsAreReadableButEditingIsPermissionControlled() throws Exception {
        String admin=login("陈静"), user=login("张伟"), viewer=login("赵敏"), it=login("李强");
        ObjectNode edit=assetEdit(admin,"ASSET-TEST-1");
        for(String token:List.of(user,viewer)) {
            body(mvc.perform(get("/assets/ASSET-TEST-1").header("Authorization","Bearer "+token)));
            body(mvc.perform(get("/assets/ASSET-TEST-1/qrcode").header("Authorization","Bearer "+token)));
            putAsset(token,"ASSET-TEST-1",edit).andExpect(status().isForbidden());
        }
        ObjectNode policy=policy(admin); removePermission(policy,"IT","asset.create"); savePolicy(admin,policy);
        putAsset(it,"ASSET-TEST-1",edit).andExpect(status().isForbidden());
        mvc.perform(get("/assets/DOES-NOT-EXIST").header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
        putAsset(admin,"DOES-NOT-EXIST",edit).andExpect(status().isNotFound());
    }
}
