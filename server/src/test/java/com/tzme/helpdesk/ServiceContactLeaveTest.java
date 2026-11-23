package com.tzme.helpdesk;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.DutySettings;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.service.DingTalkService;
import com.tzme.helpdesk.service.RuntimeSettingsService;
import com.tzme.helpdesk.service.ServiceContactService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceContactLeaveTest {
    @Test void leaveLookupUsesTheDocumentedBatchAndPagination() {
        var props = new HelpdeskProperties();
        props.setRedisEnabled(false);
        var dingTalk = new DingTalkService(props, null);
        var server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(dingTalk, "rest")).build();
        server.expect(requestTo(props.getDingtalk().getTokenUrl() + "?appkey=&appsecret="))
                .andRespond(withSuccess("{\"errcode\":0,\"access_token\":\"fixture\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        String url = props.getDingtalk().getLeaveStatusUrl() + "?access_token=fixture";
        server.expect(requestTo(url)).andExpect(jsonPath("$.userid_list").value("ding-A,ding-B"))
                .andExpect(jsonPath("$.start_time").value(1795363200000L))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.offset").value(0))
                .andRespond(withSuccess("""
                    {"errcode":0,"success":true,"result":{"has_more":true,
                    "leave_status":[{"userid":"ding-A","start_time":1795366800000,"end_time":1795370400000}]}}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andExpect(jsonPath("$.offset").value(20))
                .andRespond(withSuccess("""
                    {"errcode":0,"success":true,"result":{"has_more":false,
                    "leave_status":[{"userid":"ding-B","start_time":1795381200000,"end_time":1795384800000}]}}
                    """, MediaType.APPLICATION_JSON));
        var result = dingTalk.leavePeriods(List.of("ding-A","ding-B"), Instant.ofEpochMilli(1795363200000L),
                Instant.ofEpochMilli(1795449599999L));
        assertEquals(List.of("ding-A", "ding-B"), result.stream().map(DingTalkService.LeavePeriod::userId).toList());
        server.verify();
    }

    @Test void linkedIdentityWinsAndUnavailableLookupIsDistinctFromNoLeave() {
        var runtime = new RuntimeSettings();
        runtime.setDuty(new DutySettings(List.of(new DutySettings.Member("值守","同名",2L),
                new DutySettings.Member("支持","同名"), new DutySettings.Member("服务台","唯一人员")), "8300", ""));
        var settings = mock(RuntimeSettingsService.class);
        when(settings.get()).thenReturn(runtime);
        var users = mock(UserMapper.class);
        when(users.listActive()).thenReturn(List.of(person(1,"同名","ding-A"),person(2,"同名","ding-B"),person(3,"唯一人员","ding-C")));
        var dingTalk = mock(DingTalkService.class);
        when(dingTalk.enabled()).thenReturn(true);
        Instant now = Instant.now();
        var ids = List.of("ding-B", "ding-C");
        when(dingTalk.leavePeriods(eq(ids),any(),any())).thenReturn(List.of(
                new DingTalkService.LeavePeriod("ding-B",now.minus(1,ChronoUnit.HOURS),now.plus(1,ChronoUnit.HOURS))));
        var service = new ServiceContactService(settings, users, dingTalk);
        assertEquals(List.of("ON_LEAVE","UNLINKED","NONE"), statuses(service));
        assertEquals(List.of("ON_LEAVE","UNLINKED","NONE"), statuses(service));
        verify(dingTalk, times(1)).leavePeriods(eq(ids),any(),any());
        when(dingTalk.leavePeriods(eq(ids),any(),any())).thenThrow(new BizException(502,"无权限"));
        assertEquals(List.of("UNKNOWN","UNLINKED","UNKNOWN"), statuses(new ServiceContactService(settings, users, dingTalk)));
    }

    private List<String> statuses(ServiceContactService service) {
        return service.roster().contacts().stream().map(ServiceContactService.Contact::status).toList();
    }

    private User person(long id, String name, String dingId) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setDingUserId(dingId);
        return user;
    }
}
