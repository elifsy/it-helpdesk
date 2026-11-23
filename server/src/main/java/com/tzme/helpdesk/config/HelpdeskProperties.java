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
        /**
         * 仅本地联调用：钉钉接口照常走真实环境，但登录仍可用演示账号切换。
         * 生产环境必须保持 false，否则任何人可以用姓名登录。
         */
        private boolean demoLogin = false;
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
        private String leaveStatusUrl = "https://oapi.dingtalk.com/topapi/attendance/getleavestatus";
        private String deptListUrl = "https://oapi.dingtalk.com/topapi/v2/department/listsub";
        private String userListUrl = "https://oapi.dingtalk.com/topapi/v2/user/list";
        /** 钉钉审批实例（发起 POST / 查询 GET 同一地址） */
        private String approvalUrl = "https://api.dingtalk.com/v1.0/workflow/processInstances";
        /** 按模板名称查询 processCode */
        private String processByNameUrl = "https://oapi.dingtalk.com/topapi/process/get_by_name";
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
