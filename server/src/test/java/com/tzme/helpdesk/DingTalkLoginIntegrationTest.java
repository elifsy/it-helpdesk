package com.tzme.helpdesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.job.ContactSyncJob;
import com.tzme.helpdesk.job.SlaScanJob;
import com.tzme.helpdesk.service.DingTalkAccountService;
import com.tzme.helpdesk.service.DingTalkService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 使用真实认证/建档/权限链路与内存数据库，所有钉钉 HTTP 均拦截，不读取或调用真实凭据。 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:helpdesk-dingtalk-login;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts="/db/fixture.sql", config=@SqlConfig(encoding="UTF-8"))
class DingTalkLoginIntegrationTest {
    private static final String BASE = "http://127.0.0.1:1/dingtalk";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired HelpdeskProperties props;
    @Autowired DingTalkService dingTalk;
    @Autowired DingTalkAccountService accounts;
    @Autowired javax.sql.DataSource dataSource;
    @MockBean SlaScanJob slaJob;
    @MockBean ContactSyncJob contactJob;
    MockRestServiceServer http;

    @BeforeEach void setup() {
        props.getDingtalk().setEnabled(true);
        props.getDingtalk().setAppKey("fake-key");
        props.getDingtalk().setAppSecret("fake-secret");
        props.getDingtalk().setBootstrapAdminUserId("");
        props.getDingtalk().setTokenUrl(BASE + "/token");
        props.getDingtalk().setUserInfoUrl(BASE + "/code");
        props.getDingtalk().setUserDetailUrl(BASE + "/profile");
        ReflectionTestUtils.setField(dingTalk, "localToken", null);
        ReflectionTestUtils.setField(dingTalk, "localTokenExpires", 0L);
        http = MockRestServiceServer.createServer((RestTemplate) ReflectionTestUtils.getField(dingTalk, "rest"));
    }

    @AfterEach void cleanup() {
        CurrentUser.clear();
        props.getDingtalk().setEnabled(false);
        props.getDingtalk().setBootstrapAdminUserId("");
        http.verify();
    }

    void token() {
        http.expect(requestTo(BASE + "/token?appkey=fake-key&appsecret=fake-secret"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"errcode\":0,\"access_token\":\"fake-token\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
    }

    void code(String userId) throws Exception {
        http.expect(requestTo(BASE + "/code?access_token=fake-token"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"code\":\"valid-code\"}"))
                .andRespond(withSuccess(json.writeValueAsString(Map.of("errcode", 0, "result", Map.of("userid", userId))), MediaType.APPLICATION_JSON));
    }

    void profile(String requestedId, Map<String, Object> profile) throws Exception {
        http.expect(requestTo(BASE + "/profile?access_token=fake-token"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json(json.writeValueAsString(Map.of("userid", requestedId, "language", "zh_CN"))))
                .andRespond(withSuccess(json.writeValueAsString(Map.of("errcode", 0, "result", profile)), MediaType.APPLICATION_JSON));
    }

    Map<String, Object> profile(String id, String name) {
        return Map.of("userid", id, "name", name, "job_number", "REAL-001", "dept_id_list", List.of(9001L));
    }

    ResultActions login() throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"authCode\":\"valid-code\"}"));
    }

    JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    int users(String id) { return db.queryForObject("select count(*) from sys_user where ding_user_id=?", Integer.class, id); }
    int bootstrapCount() { return db.queryForObject("select count(*) from sys_setting where setting_key='dingtalk-bootstrap-admin'", Integer.class); }

    @Test void badgeUsesVerifiedDingTalkAvatarByDefaultAndCachesTheProfile() throws Exception {
        String id = "badge-avatar-user";
        var person = new java.util.HashMap<>(profile(id, "工牌头像员工"));
        person.put("avatar", "https://static.dingtalk.com/media/badge-avatar.png");
        token(); code(id); profile(id, person); profile(id, person);
        String token = data(login()).path("token").asText();
        for (int i = 0; i < 2; i++) {
            JsonNode badge = data(mvc.perform(get("/account/badge").header("Authorization", "Bearer " + token)));
            assertEquals("DINGTALK", badge.path("avatarSource").asText());
            assertEquals(person.get("avatar"), badge.path("avatarUrl").asText());
        }
    }

    @Test void firstLoginRegistersVerifiedUserAndCanUseServiceWithoutDirectorySync() throws Exception {
        db.update("update sys_dept set ding_dept_id='9001' where id=2");
        token(); code("real-first"); profile("real-first", profile("real-first", "真实员工"));
        JsonNode user = data(login());
        assertEquals("真实员工", user.get("name").asText());
        assertEquals("研发部", user.get("deptName").asText());
        assertEquals("USER", user.get("role").asText()); assertEquals(1, users("real-first"));
        assertEquals("REAL-001", db.queryForObject("select employee_no from sys_user where ding_user_id='real-first'", String.class));
        String token = user.get("token").asText();
        JsonNode me = data(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)));
        assertEquals("real-first", me.get("dingUserId").asText());
        mvc.perform(get("/tickets").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/admin/manage/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertEquals(0, bootstrapCount());
    }

    @Test void missingDepartmentDoesNotPreventLoginOrRequireBroaderDirectoryPermission() throws Exception {
        token(); code("unsynced-dept"); profile("unsynced-dept", profile("unsynced-dept", "新员工"));
        assertEquals("USER", data(login()).get("role").asText());
        assertEquals(0L, db.queryForObject("select dept_id from sys_user where ding_user_id='unsynced-dept'", Long.class));
    }

    @Test void upgradingLegacyDepartmentTableAllowsFirstLoginWithoutLosingExistingUsers() throws Exception {
        var department = db.queryForMap("select id,name,parent_id,sort,created_at from sys_dept where id=2");
        db.execute("alter table sys_dept drop column ding_dept_id");
        new com.tzme.helpdesk.config.ManagementSchemaInitializer(dataSource).afterPropertiesSet();
        assertEquals(department, db.queryForMap("select id,name,parent_id,sort,created_at from sys_dept where id=2"));
        token(); code("legacy-first-login"); profile("legacy-first-login", profile("legacy-first-login", "旧库登录员工"));
        JsonNode user = data(login());
        assertEquals("USER", user.get("role").asText()); assertEquals(1, users("legacy-first-login"));
        assertEquals(7, db.queryForObject("select count(*) from sys_user", Integer.class));
        assertEquals("ADMIN", db.queryForObject("select role from sys_user where id=3", String.class));
    }

    @Test void repeatLoginKeepsExistingRoleAndDoesNotReadProfileAgain() throws Exception {
        token(); code("repeat-user"); profile("repeat-user", profile("repeat-user", "工程师")); code("repeat-user");
        data(login());
        db.update("update sys_user set role='IT',skill='网络',location='机房' where ding_user_id='repeat-user'");
        assertEquals("IT", data(login()).get("role").asText());
        assertEquals(1, users("repeat-user"));
        assertEquals("网络", db.queryForObject("select skill from sys_user where ding_user_id='repeat-user'", String.class));
    }

    @Test void sameNameAsLocalAdminCreatesIndependentOrdinaryUser() throws Exception {
        token(); code("real-same-name"); profile("real-same-name", profile("real-same-name", "陈静"));
        assertEquals("USER", data(login()).get("role").asText());
        assertEquals("ADMIN", db.queryForObject("select role from sys_user where id=3", String.class));
        assertEquals(2, db.queryForObject("select count(*) from sys_user where name='陈静'", Integer.class));
    }

    @Test void disabledAccountCannotReactivateOrBootstrapThroughLogin() throws Exception {
        db.update("update sys_user set ding_user_id='disabled-real',status='DISABLED' where id=4");
        props.getDingtalk().setBootstrapAdminUserId("disabled-real");
        token(); code("disabled-real");
        login().andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("账号已停用"));
        assertEquals("DISABLED", db.queryForObject("select status from sys_user where id=4", String.class));
        assertEquals("IT", db.queryForObject("select role from sys_user where id=4", String.class));
        assertEquals(0, bootstrapCount());
    }

    @Test void mismatchedProfileCannotCreateOrElevateAccount() throws Exception {
        props.getDingtalk().setBootstrapAdminUserId("claimed-admin");
        token(); code("claimed-admin"); profile("claimed-admin", profile("different-person", "管理员"));
        login().andExpect(status().isBadGateway());
        assertEquals(0, users("claimed-admin")); assertEquals(0, users("different-person")); assertEquals(0, bootstrapCount());
    }

    @Test void incompleteProfileDoesNotCreatePlaceholderAccount() throws Exception {
        token(); code("no-name"); profile("no-name", Map.of("userid", "no-name"));
        login().andExpect(status().isBadGateway()); assertEquals(0, users("no-name"));
    }

    @Test void deniedProfilePermissionGivesActionableErrorAndCanRetry() throws Exception {
        token(); code("restricted-user");
        http.expect(requestTo(BASE + "/profile?access_token=fake-token"))
                .andRespond(withSuccess("{\"errcode\":60011,\"errmsg\":\"sensitive-provider-detail\"}", MediaType.APPLICATION_JSON));
        code("restricted-user"); profile("restricted-user", profile("restricted-user", "授权后员工"));
        String error = login().andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertTrue(error.contains("成员信息读取权限")); assertFalse(error.contains("sensitive-provider-detail"));
        assertFalse(error.contains("fake-token")); assertEquals(0, users("restricted-user"));
        assertEquals("USER", data(login()).get("role").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub_code", "subCode", "subcode"})
    void wrappedPermissionDenialShowsActualCauseAndRequiredPermission(String key) throws Exception {
        token(); code("missing-permission");
        http.expect(requestTo(BASE + "/profile?access_token=fake-token"))
                .andRespond(withSuccess(json.writeValueAsString(Map.of("errcode", 88, key, "60011",
                        "sub_msg", "qyapi_get_member fake-secret sensitive-provider-detail")), MediaType.APPLICATION_JSON));
        String error = login().andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertTrue(error.contains("88/60011")); assertTrue(error.contains("qyapi_get_member"));
        assertTrue(error.contains("权限管理")); assertFalse(error.contains("fake-secret"));
        assertFalse(error.contains("sensitive-provider-detail")); assertEquals(0, users("missing-permission"));
    }

    @Test void wrappedErrorCanReadSubCodeFromLegacyMessageWithoutExposingMessage() throws Exception {
        token(); code("legacy-permission");
        http.expect(requestTo(BASE + "/profile?access_token=fake-token"))
                .andRespond(withSuccess("{\"errcode\":88,\"errmsg\":\"ding talk error[subcode=60011,submsg=sensitive-provider-detail]\"}", MediaType.APPLICATION_JSON));
        String error = login().andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertTrue(error.contains("88/60011")); assertTrue(error.contains("qyapi_get_member"));
        assertFalse(error.contains("sensitive-provider-detail")); assertEquals(0, users("legacy-permission"));
    }

    @Test void differentWrappedErrorIsNotMisreportedAsPermissionDenied() throws Exception {
        token(); code("invalid-token");
        http.expect(requestTo(BASE + "/profile?access_token=fake-token"))
                .andRespond(withSuccess("{\"errcode\":88,\"sub_code\":40014,\"errmsg\":\"sensitive-provider-detail\"}", MediaType.APPLICATION_JSON));
        String error = login().andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertTrue(error.contains("88/40014")); assertFalse(error.contains("qyapi_get_member"));
        assertFalse(error.contains("sensitive-provider-detail")); assertEquals(0, users("invalid-token"));
    }

    @Test void failedCodeCannotReadProfileOrCreateAccount() throws Exception {
        token();
        http.expect(requestTo(BASE + "/code?access_token=fake-token"))
                .andRespond(withSuccess("{\"errcode\":40078,\"errmsg\":\"invalid code\"}", MediaType.APPLICATION_JSON));
        login().andExpect(status().isUnauthorized());
        assertEquals(6, db.queryForObject("select count(*) from sys_user", Integer.class));
    }

    @Test void emptyVerifiedUserIdIsRejected() throws Exception {
        token(); code("  "); login().andExpect(status().isUnauthorized());
        assertEquals(6, db.queryForObject("select count(*) from sys_user", Integer.class));
    }

    @Test void explicitlyConfiguredAdministratorIsInitializedOnceAndLaterRoleChangesRemainEffective() throws Exception {
        props.getDingtalk().setBootstrapAdminUserId("trusted-admin");
        token(); code("trusted-admin"); profile("trusted-admin", profile("trusted-admin", "正式管理员")); code("trusted-admin");
        code("second-candidate"); profile("second-candidate", profile("second-candidate", "其他人员"));
        JsonNode admin = data(login()); assertEquals("ADMIN", admin.get("role").asText());
        mvc.perform(get("/admin/manage/users").header("Authorization", "Bearer " + admin.get("token").asText())).andExpect(status().isOk());
        assertEquals(1, bootstrapCount());
        assertEquals(1, db.queryForObject("select count(*) from sys_audit_log where action='DINGTALK_ADMIN_BOOTSTRAP'", Integer.class));
        db.update("update sys_user set role='USER' where ding_user_id='trusted-admin'");
        assertEquals("USER", data(login()).get("role").asText());
        props.getDingtalk().setBootstrapAdminUserId("second-candidate");
        assertEquals("USER", data(login()).get("role").asText());
    }

    @Test void configuredAdministratorCanBeAnAlreadySyncedAccount() throws Exception {
        props.getDingtalk().setBootstrapAdminUserId("synced-admin");
        db.update("update sys_user set ding_user_id='synced-admin' where id=1");
        token(); code("synced-admin");
        JsonNode admin = data(login()); assertEquals(1L, admin.get("id").asLong()); assertEquals("ADMIN", admin.get("role").asText());
        assertEquals(1, bootstrapCount());
    }

    @Test void clientCannotChooseUserIdOrRoleToClaimConfiguredAdministrator() throws Exception {
        props.getDingtalk().setBootstrapAdminUserId("trusted-admin");
        token(); code("ordinary-user"); profile("ordinary-user", profile("ordinary-user", "普通人员"));
        JsonNode user = data(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"authCode\":\"valid-code\",\"dingUserId\":\"trusted-admin\",\"role\":\"ADMIN\",\"demoUser\":\"陈静\"}")));
        assertEquals("ordinary-user", user.get("dingUserId").asText()); assertEquals("USER", user.get("role").asText());
        assertEquals(0, bootstrapCount());
    }

    @Test void concurrentFirstLoginsShareOneLocalAccount() throws Exception {
        var profile = new DingTalkService.UserProfile("concurrent-user", "并发员工", null, List.of());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> register = () -> { start.await(); return accounts.resolve(profile.userId(), profile).getId(); };
            Future<Long> first = pool.submit(register), second = pool.submit(register); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, users("concurrent-user"));
            assertEquals(1, db.queryForObject("select count(*) from sys_audit_log where action='DINGTALK_USER_REGISTER'", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
