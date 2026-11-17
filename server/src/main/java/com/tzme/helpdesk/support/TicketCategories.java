package com.tzme.helpdesk.support;

import java.util.*;

/** 问题类型与分类的默认关系；旧字典只迁移到故障分类，不把故障项目混入申请或咨询。 */
public final class TicketCategories {
    public static final List<String> TYPES = List.of("INCIDENT", "SERVICE_REQUEST", "QUESTION");
    private TicketCategories() {}

    public static Map<String, Map<String, List<String>>> fromLegacy(Map<String, List<String>> legacy) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        Map<String, List<String>> incidents = new LinkedHashMap<>();
        Set<String> requests = Set.of("账号开通", "密码重置", "离职停用", "软件安装");
        if (legacy != null) legacy.forEach((name, children) -> {
            List<String> faults = children.stream().filter(child -> !requests.contains(child)).toList();
            if (!faults.isEmpty()) incidents.put(name, new ArrayList<>(faults));
        });
        result.put("INCIDENT", incidents);
        Map<String, List<String>> services = new LinkedHashMap<>();
        services.put("账号", List.of("账号开通", "密码重置", "权限申请", "离职停用", "其他"));
        services.put("系统", List.of("软件安装", "系统开通", "邮箱开通", "共享盘权限申请", "其他"));
        services.put("网络", List.of("VPN开通", "网络接入申请", "其他"));
        services.put("终端", List.of("设备配置", "外设安装", "其他"));
        services.put("打印", List.of("打印机安装", "扫描配置", "其他"));
        services.put("会议室", List.of("会议支持", "设备配置", "其他"));
        services.put("其他", List.of("其他"));
        result.put("SERVICE_REQUEST", services);
        Map<String, List<String>> questions = new LinkedHashMap<>();
        questions.put("系统", List.of("软件使用", "邮箱使用", "共享盘使用", "ERP/PLM使用", "其他"));
        questions.put("网络", List.of("联网指导", "VPN使用", "其他"));
        questions.put("终端", List.of("设备使用", "外设使用", "其他"));
        questions.put("打印", List.of("打印扫描指导", "耗材更换指导", "其他"));
        questions.put("会议室", List.of("投屏指导", "音视频使用", "其他"));
        questions.put("账号", List.of("登录指导", "权限咨询", "其他"));
        questions.put("其他", List.of("其他"));
        result.put("QUESTION", questions);
        return result;
    }

    public static Map<String, List<String>> merge(Map<String, Map<String, List<String>>> byType) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        byType.values().forEach(categories -> categories.forEach((name, children) -> {
            List<String> merged = result.computeIfAbsent(name, ignored -> new ArrayList<>());
            children.forEach(child -> { if (!merged.contains(child)) merged.add(child); });
        }));
        return result;
    }
}
