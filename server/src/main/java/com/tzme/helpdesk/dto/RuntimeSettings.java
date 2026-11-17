package com.tzme.helpdesk.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;

@Data
public class RuntimeSettings {
    @NotNull @Min(0) private Long revision = 0L;
    @NotNull private Boolean kbRecommendEnabled = true;
    @NotNull private Boolean requireSolutionCode = true;
    @NotNull private Boolean slaScanEnabled = true;
    @NotNull private Boolean contactSyncEnabled = true;
    @NotNull @Min(1) @Max(8760) private Integer p1Hours = 4;
    @NotNull @Min(1) @Max(8760) private Integer p2Hours = 8;
    @NotNull @Min(1) @Max(8760) private Integer p3Hours = 16;
    @NotNull @Min(1) @Max(8760) private Integer p4Hours = 40;
    @NotBlank @Size(max=255) private String qrcodeBaseUrl = "http://localhost:5173";
    @NotNull @Min(1) @Max(10) private Integer maxUploadMb = 5;
    @NotNull @Min(1) @Max(20) private Integer maxAttachments = 3;
    @NotNull @Min(0) @Max(300) private Integer statsCacheSeconds = 60;
    @NotEmpty @Size(max=30) private Map<String, List<String>> categories = defaultCategories();
    private Map<String, Map<String, List<String>>> categoriesByType;

    public int hoursOf(String priority) {
        return switch (priority) { case "P1" -> p1Hours; case "P2" -> p2Hours; case "P3" -> p3Hours; default -> p4Hours; };
    }
    public static Map<String, List<String>> defaultCategories() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("网络", List.of("无线连不上", "有线故障", "VPN", "网速慢", "其他"));
        m.put("终端", List.of("无法开机", "蓝屏/死机", "硬件故障", "外设异常", "其他"));
        m.put("系统", List.of("账号权限", "软件安装", "共享盘", "邮箱", "ERP/PLM", "其他"));
        m.put("打印", List.of("卡纸", "脱机", "耗材", "扫描", "其他"));
        m.put("会议室", List.of("投屏", "音视频", "其他"));
        m.put("账号", List.of("密码重置", "账号开通", "离职停用"));
        m.put("信息安全", List.of("病毒/木马", "钓鱼邮件", "数据泄露风险"));
        m.put("其他", List.of("其他"));
        return m;
    }
}
