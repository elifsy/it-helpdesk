package com.tzme.helpdesk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "helpdesk")
public class HelpdeskProperties {
    private Jwt jwt = new Jwt();
    private DingTalk dingtalk = new DingTalk();
    private Sla sla = new Sla();
    private Qrcode qrcode = new Qrcode();
    private Upload upload = new Upload();
    private String demoUser = "张伟";
    /** 本地没有 Redis 时置 false：单号走时间戳、看板不缓存、定时任务不加锁 */
    private boolean redisEnabled = true;

    @Data
    public static class Jwt {
        private String secret = "tzme-helpdesk-change-this-secret-in-production-2026";
        private int expireHours = 12;
    }

    @Data
    public static class DingTalk {
        private boolean enabled = false;
        private String corpId = "";
        private String appKey = "";
        private String appSecret = "";
        private String agentId = "";
        /** 仅初始化一次的管理员；必须与钉钉免登验证后的真实 userid 完全一致。 */
        private String bootstrapAdminUserId = "";
        private String tokenUrl = "https://oapi.dingtalk.com/gettoken";
        private String userInfoUrl = "https://oapi.dingtalk.com/topapi/v2/user/getuserinfo";
        private String userDetailUrl = "https://oapi.dingtalk.com/topapi/v2/user/get";
        private String workNotifyUrl = "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2";
        private String deptListUrl = "https://oapi.dingtalk.com/topapi/v2/department/listsub";
        private String userListUrl = "https://oapi.dingtalk.com/topapi/v2/user/list";
    }

    @Data
    public static class Sla {
        private int p1Hours = 4;
        private int p2Hours = 8;
        private int p3Hours = 16;
        private int p4Hours = 40;

        public int hoursOf(String priority) {
            return switch (priority) {
                case "P1" -> p1Hours;
                case "P2" -> p2Hours;
                case "P3" -> p3Hours;
                default -> p4Hours;
            };
        }
    }

    @Data
    public static class Qrcode {
        private String baseUrl = "http://localhost:5173";
    }

    @Data
    public static class Upload {
        /** 附件落盘目录，相对路径以启动目录为基准 */
        private String dir = "./data/uploads";
    }
}
