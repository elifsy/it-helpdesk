package com.tzme.helpdesk.security;

import com.tzme.helpdesk.common.*;
import java.util.*;

public final class Permissions {
    public record Capability(String key, String name, String group, boolean readOnly, List<String> requires) { }
    public static final List<Capability> CATALOG = List.of(
        cap("ticket.view.own","查看本人工单","工单",true),
        cap("ticket.view.all","查看全部工单","工单",true),
        cap("ticket.create","提交报修","工单",false,"ticket.view.own"),
        cap("ticket.confirm","本人确认、评价与撤销","工单",false,"ticket.view.own"),
        cap("ticket.accept","受理接单与驳回","工单",false,"ticket.view.all"),
        cap("ticket.dispatch","派单","工单",false,"ticket.view.all"),
        cap("ticket.handle","处理记录与提交完成","工单",false,"ticket.view.all"),
        cap("asset.view","查看资产与二维码","资产",true),
        cap("asset.create","新增、编辑资产与管理分类","资产",false,"asset.view"),
        cap("request.view.own","查看本人资产申请","资产",true),
        cap("request.view.all","查看全部资产申请","资产",true),
        cap("request.create","发起与撤销本人申请","资产",false,"asset.view","request.view.own"),
        cap("request.approve","审批资产申请","资产",false,"asset.view","request.view.all"),
        cap("kb.view","查看知识库","知识与数据",true),
        cap("kb.vote","知识库反馈","知识与数据",false,"kb.view"),
        cap("kb.manage","整理知识草稿","知识与数据",false,"kb.view"),
        cap("kb.publish","发布与撤回知识","知识与数据",false,"kb.manage"),
        cap("stats.view","查看统计看板","知识与数据",true),
        cap("attachment.upload","上传业务附件","附件",false),
        cap("attachment.delete","删除业务附件","附件",false),
        cap("user.manage","用户与角色管理","系统管理",false),
        cap("dept.manage","部门管理","系统管理",false),
        cap("settings.manage","运行配置、SLA 与存储","系统管理",false),
        cap("dict.manage","分类与解决代码管理","系统管理",false),
        cap("audit.view","查看审计日志","系统管理",true),
        cap("contact.sync","同步通讯录","系统管理",false),
        cap("role.manage","配置角色权限","系统管理",false)
    );
    public static final List<String> SELF_SERVICE = List.of("ticket.view.own", "ticket.create", "ticket.confirm", "asset.view",
            "request.view.own", "request.create", "kb.view", "kb.vote", "attachment.upload", "attachment.delete");
    private Permissions() { }
    private static Capability cap(String key,String name,String group,boolean readOnly,String... requires) {
        return new Capability(key,name,group,readOnly,List.of(requires));
    }
    public static List<String> defaults(String role) {
        if (Roles.isAdmin(role)) return CATALOG.stream().map(Capability::key).toList();
        List<String> result = new ArrayList<>(SELF_SERVICE);
        if ("VIEWER".equals(role)) result.addAll(List.of("ticket.view.all","request.view.all","stats.view"));
        if ("IT".equals(role)) result.addAll(List.of("ticket.view.all","ticket.accept","ticket.dispatch","ticket.handle","asset.create","request.view.all","request.approve","stats.view","kb.manage"));
        return result;
    }
    public static void require(String key) {
        if (!CurrentUser.require().hasPermission(key)) throw new BizException(403,"当前角色没有该操作权限");
    }
}
