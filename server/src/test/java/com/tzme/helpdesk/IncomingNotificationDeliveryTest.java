package com.tzme.helpdesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.Notify;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.mapper.NotifyMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.service.AuditService;
import com.tzme.helpdesk.service.DingTalkService;
import com.tzme.helpdesk.service.IncomingNotificationService;
import com.tzme.helpdesk.service.NotifyService;
import com.tzme.helpdesk.service.RolePermissionService;
import com.tzme.helpdesk.service.RuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IncomingNotificationDeliveryTest {
    private static final String TICKET_LINK = "dingtalk://dingtalkclient/action/openapp?corpid=ding-fixture"
            + "&container_type=work_platform&app_id=0_100&redirect_type=jump"
            + "&redirect_url=https%3A%2F%2Fhelpdesk.example%2Fams%2F%3Fnotice%3D%252Fticket%252FIT-TEST-0001";

    @Test void newTicketCardOpensTheTicketForTheExactDingTalkIdsAfterCommit() {
        var props = new HelpdeskProperties();
        props.setRedisEnabled(false);
        props.getDingtalk().setEnabled(true);
        props.getDingtalk().setCorpId("ding-fixture");
        props.getDingtalk().setAgentId("100");
        var dingTalk = new DingTalkService(props, null);
        var server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(dingTalk, "rest")).build();
        var users = mock(UserMapper.class);
        var notifies = mock(NotifyMapper.class);
        var permissions = mock(RolePermissionService.class);
        var notify = new NotifyService(notifies, users, dingTalk, permissions, settings());
        var recipients = List.of(user(11L, "ding-account-11"), user(12L, "ding-account-12"));
        recipients.forEach(person -> {
            when(users.findById(person.getId())).thenReturn(person);
            when(permissions.resolve(person)).thenReturn(new LoginUser(person.getId(), person.getName(), "IT", "技术中心"));
        });
        var management = mock(ManagementMapper.class);
        when(management.setting("incoming-notifications")).thenReturn("""
                {"revision":1,"ticketRules":[{"type":"INCIDENT","cat1":"终端","recipientIds":[11,12]}],"assetRecipients":{}}
                """);
        var service = incoming(management, users, notify);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.newTicket(ticket());
            server.verify();
            server.expect(requestTo(props.getDingtalk().getTokenUrl() + "?appkey=&appsecret="))
                    .andRespond(withSuccess("{\"errcode\":0,\"access_token\":\"fixture\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(props.getDingtalk().getWorkNotifyUrl() + "?access_token=fixture"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.userid_list").value("ding-account-11,ding-account-12"))
                    .andExpect(jsonPath("$.msg.msgtype").value("action_card"))
                    .andExpect(jsonPath("$.msg.action_card.title").value("新工单待受理"))
                    .andExpect(jsonPath("$.msg.action_card.single_title").value("去受理"))
                    .andExpect(jsonPath("$.msg.action_card.single_url").value(TICKET_LINK))
                    .andRespond(withSuccess("{\"errcode\":0,\"task_id\":1}", MediaType.APPLICATION_JSON));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            server.verify();
            verify(users, never()).findByName(anyString());
            for (User person : recipients) {
                verify(notifies).insert(argThat(notification -> notification != null
                        && person.getId().equals(notification.getRecipientId()) && "IT-TEST-0001".equals(notification.getRefNo())));
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void permissionFallbackUsesStaffAccountsAndKeepsTheTicketLink() {
        var dingTalk = mock(DingTalkService.class);
        when(dingTalk.enabled()).thenReturn(true);
        when(dingTalk.workAppLink("https://helpdesk.example/ams/?notice=%2Fticket%2FIT-TEST-0001")).thenReturn(TICKET_LINK);
        var users = mock(UserMapper.class);
        var notifies = mock(NotifyMapper.class);
        var permissions = mock(RolePermissionService.class);
        var engineer = user(11L, "ding-it");
        var admin = user(12L, null);
        admin.setRole("ADMIN");
        var ordinary = user(13L, "ding-user");
        ordinary.setRole("USER");
        when(users.listActive()).thenReturn(List.of(engineer, admin, ordinary));
        for (User person : List.of(engineer, admin, ordinary)) {
            when(permissions.resolve(person)).thenReturn(new LoginUser(person.getId(), person.getName(),
                    person.getRole(), "技术中心", List.of("ticket.accept", "ticket.view.all")));
        }
        var service = incoming(mock(ManagementMapper.class), users, new NotifyService(notifies, users, dingTalk, permissions, settings()));

        service.newTicket(ticket());

        verify(dingTalk).sendWorkNotify("ding-it", "新工单待受理", "IT-TEST-0001 测试设备（P3）", TICKET_LINK, "去受理");
        verify(notifies).insert(argThat(notification -> notification != null && Long.valueOf(11).equals(notification.getRecipientId())));
        verify(notifies).insert(argThat(notification -> notification != null && Long.valueOf(12).equals(notification.getRecipientId())));
        verifyNoMoreInteractions(notifies);
        verify(users, never()).findByName(anyString());
    }

    @Test void purchaseNotificationsSendDifferentButtonsToApproverAndApplicant() {
        var dingTalk = mock(DingTalkService.class);
        when(dingTalk.enabled()).thenReturn(true);
        var users = mock(UserMapper.class);
        var notifies = mock(NotifyMapper.class);
        var permissions = mock(RolePermissionService.class);
        var approver = user(11L, "ding-it");
        var applicant = user(12L, "ding-user");
        applicant.setRole("USER");
        when(users.listActive()).thenReturn(List.of(approver, applicant));
        when(users.findById(12L)).thenReturn(applicant);
        for (User person : List.of(approver, applicant)) {
            when(permissions.resolve(person)).thenReturn(new LoginUser(person.getId(), person.getName(),
                    person.getRole(), "技术中心"));
        }
        var service = new NotifyService(notifies, users, dingTalk, permissions, settings());
        service.notifyPermission("request.approve", "有新的设备购入申请", "待审批", "PURCHASE", "PR-01");
        service.send(12L, applicant.getName(), "购入申请已提交", "等待审批", "PURCHASE", "PR-01");

        verify(dingTalk).sendWorkNotify("ding-it", "有新的设备购入申请", "待审批",
                "https://helpdesk.example/ams/?notice=%2Fadmin%2Fpurchase%3Frequest%3DPR-01", "去审批");
        verify(dingTalk).sendWorkNotify("ding-user", "购入申请已提交", "等待审批",
                "https://helpdesk.example/ams/?notice=%2Fassets%3Fpurchase%3DPR-01", "查看购入申请");
        verify(users, never()).findByName(anyString());
    }

    @Test void overdueTicketsLinkToTheOriginalTicketInBothChannels() {
        var dingTalk = mock(DingTalkService.class);
        when(dingTalk.enabled()).thenReturn(true);
        var users = mock(UserMapper.class);
        var notifies = mock(NotifyMapper.class);
        var permissions = mock(RolePermissionService.class);
        var engineer = user(11L, "ding-it");
        var login = new LoginUser(11L, engineer.getName(), "IT", "技术中心");
        when(users.findById(11L)).thenReturn(engineer);
        when(permissions.resolve(engineer)).thenReturn(login);
        var item = new Notify();
        item.setTitle("工单已超时");
        item.setType("SLA");
        item.setRefNo("IT-TEST-0001");
        when(notifies.listByUser(11L, engineer.getName())).thenReturn(List.of(item));

        var service = new NotifyService(notifies, users, dingTalk, permissions, settings());
        service.send(11L, engineer.getName(), "工单已超时", "请尽快处理", "SLA", "IT-TEST-0001");
        verify(dingTalk).sendWorkNotify("ding-it", "工单已超时", "请尽快处理",
                "https://helpdesk.example/ams/?notice=%2Fticket%2FIT-TEST-0001", "处理超时工单");
        assertEquals("/ticket/IT-TEST-0001", service.list(login).get(0).getAction().path());
    }

    private RuntimeSettingsService settings() {
        var runtime = new RuntimeSettings();
        runtime.setQrcodeBaseUrl("https://helpdesk.example/ams");
        var settings = mock(RuntimeSettingsService.class);
        when(settings.get()).thenReturn(runtime);
        return settings;
    }

    private IncomingNotificationService incoming(ManagementMapper management, UserMapper users, NotifyService notify) {
        return new IncomingNotificationService(management, users, settings(), mock(AuditService.class), notify, new ObjectMapper());
    }

    private Ticket ticket() {
        var ticket = new Ticket();
        ticket.setNo("IT-TEST-0001");
        ticket.setType("INCIDENT");
        ticket.setCat1("终端");
        ticket.setTitle("测试设备");
        ticket.setPriority("P3");
        return ticket;
    }

    private User user(long id, String dingUserId) {
        var user = new User();
        user.setId(id);
        user.setName("同名员工");
        user.setStatus("ACTIVE");
        user.setRole("IT");
        user.setDingUserId(dingUserId);
        return user;
    }
}
