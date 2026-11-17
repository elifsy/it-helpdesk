package com.tzme.helpdesk;

import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.dto.RuntimeSettings;
import com.tzme.helpdesk.job.*;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBehaviourTest {
    @Test void dingTalkModeRejectsDemoAndLegacyTokens() {
        HelpdeskProperties props = new HelpdeskProperties();
        var jwt = new com.tzme.helpdesk.security.JwtUtil(props);
        var user = new com.tzme.helpdesk.security.LoginUser(1L,"张伟","USER","技术中心");
        String demo = jwt.create(user);
        String legacy = io.jsonwebtoken.Jwts.builder().subject("1").claim("name","张伟").claim("role","USER")
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        props.getDingtalk().setEnabled(true);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> jwt.parse(demo));
        assertThrows(io.jsonwebtoken.JwtException.class, () -> jwt.parse(legacy));
    }
    @Test void tokensIssuedForDingTalkRemainValidInDingTalkMode() {
        HelpdeskProperties props = new HelpdeskProperties(); props.getDingtalk().setEnabled(true);
        var jwt = new com.tzme.helpdesk.security.JwtUtil(props);
        String token = jwt.create(new com.tzme.helpdesk.security.LoginUser(4L,"李强","IT","技术中心"));
        assertEquals(4L, jwt.parse(token).id());
    }
    @Test void disabledSlaJobDoesNotScanOrSendNotifications() {
        RuntimeSettingsService settings=mock(RuntimeSettingsService.class);
        RuntimeSettings config=new RuntimeSettings(); config.setSlaScanEnabled(false); when(settings.get()).thenReturn(config);
        TicketService tickets=mock(TicketService.class); ManagementMapper management=mock(ManagementMapper.class);
        new SlaScanJob(tickets,mock(StringRedisTemplate.class),new HelpdeskProperties(),settings,management).scan();
        verifyNoInteractions(tickets,management);
    }
    @Test void enabledSlaJobRecordsItsActualResult() {
        RuntimeSettingsService settings=mock(RuntimeSettingsService.class); when(settings.get()).thenReturn(new RuntimeSettings());
        TicketService tickets=mock(TicketService.class); when(tickets.scanTimeout()).thenReturn(2);
        HelpdeskProperties props=new HelpdeskProperties(); props.setRedisEnabled(false);
        ManagementMapper management=mock(ManagementMapper.class);
        new SlaScanJob(tickets,mock(StringRedisTemplate.class),props,settings,management).scan();
        verify(tickets).scanTimeout(); verify(management).jobResult(eq("sla"),eq("SUCCESS"),contains("2"));
    }
    @Test void disabledContactJobNeverCallsExternalDirectory() {
        RuntimeSettingsService settings=mock(RuntimeSettingsService.class);
        RuntimeSettings config=new RuntimeSettings(); config.setContactSyncEnabled(false); when(settings.get()).thenReturn(config);
        HelpdeskProperties props=new HelpdeskProperties(); props.getDingtalk().setEnabled(true);
        DingTalkContactService contacts=mock(DingTalkContactService.class); ManagementMapper management=mock(ManagementMapper.class);
        new ContactSyncJob(contacts,props,settings,management).dailySync();
        verifyNoInteractions(contacts,management);
    }
    @Test void disabledRecommendationsSkipSearch() {
        RuntimeSettingsService settings=mock(RuntimeSettingsService.class);
        RuntimeSettings config=new RuntimeSettings(); config.setKbRecommendEnabled(false); when(settings.get()).thenReturn(config);
        KbArticleMapper articles=mock(KbArticleMapper.class);
        assertTrue(new KbService(articles,settings).recommend("终端","蓝屏").isEmpty()); verifyNoInteractions(articles);
    }
}
