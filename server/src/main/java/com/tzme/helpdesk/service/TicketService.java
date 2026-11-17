package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.*;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.security.LoginUser;
import com.tzme.helpdesk.support.PriorityCalculator;
import com.tzme.helpdesk.support.TicketStatus;
import com.tzme.helpdesk.vo.TicketDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;
    private final TicketRecordMapper recordMapper;
    private final AssetMapper assetMapper;
    private final UserMapper userMapper;
    private final NotifyService notifyService;
    private final NoGen noGen;
    private final HelpdeskProperties props;
    private final RuntimeSettingsService settings;
    private final ManagementMapper management;
    private final RolePermissionService rolePermissions;
    private final KnowledgeManagementService knowledge;

    // ---------------- 查询 ----------------

    public PageResult<Ticket> list(String status, Boolean open, String keyword, Boolean mine,
                                   String priority, String cat1, String assetNo, int page, int size) {
        LoginUser u = CurrentUser.require();
        Long creatorId = null;
        boolean mineFlag = Boolean.TRUE.equals(mine) || !u.canManageTickets();
        if (mineFlag) {
            creatorId = u.id();
        }
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(100, size));
        List<Ticket> rows = ticketMapper.list(status, open, keyword, creatorId, null, priority, cat1, assetNo, (p - 1) * s, s);
        long total = ticketMapper.count(status, open, keyword, creatorId, null, priority, cat1, assetNo);
        rows.forEach(this::fillRemain);
        fillQueue(rows);
        return new PageResult<>(rows, total, p, s);
    }

    public TicketDetailVO detail(String no) {
        LoginUser u = CurrentUser.require();
        Ticket t = requireTicket(no);
        if (!u.canManageTickets() && !t.getCreatorId().equals(u.id())) {
            throw new BizException(403, "无权查看该工单");
        }
        TicketDetailVO vo = new TicketDetailVO();
        vo.setTicket(fillRemain(t));
        fillQueue(List.of(t));
        vo.setEvents(eventMapper.listByNo(no));
        vo.setRecords(recordMapper.listByNo(no));
        if (t.getAssetNo() != null && !t.getAssetNo().isBlank()) {
            vo.setAsset(assetMapper.findByNo(t.getAssetNo()));
        }
        vo.setActions(actionsOf(t, u));
        return vo;
    }

    // ---------------- 创建 ----------------

    @Transactional
    public Ticket create(TicketCreateReq req) {
        LoginUser u = CurrentUser.require();
        var runtime = settings.get();
        String type = req.getType() == null ? "INCIDENT" : req.getType();
        var typeCategories = runtime.getCategoriesByType().get(type);
        if (typeCategories == null) throw new BizException("请选择有效的问题类型");
        List<String> categories = typeCategories.get(req.getCat1());
        if (categories == null || (req.getCat2()!=null && !req.getCat2().isBlank() && !categories.contains(req.getCat2())))
            throw new BizException("问题分类与当前问题类型不匹配，请重新选择");
        // 同一人对同一设备只允许一张未结工单，避免重复提报
        if (req.getAssetNo() != null && !req.getAssetNo().isBlank()) {
            Ticket open = ticketMapper.findOpenByCreatorAndAsset(u.id(), req.getAssetNo());
            if (open != null)
                throw new BizException(409, "该设备已有进行中的工单 " + open.getNo()
                        + "，请在「我的工单」里查看进度或补充信息，关闭后再提交新工单。");
        }
        String priority = PriorityCalculator.calc(req.getCat1(), req.getImpact(), req.getWorkImpact());
        LocalDateTime now = LocalDateTime.now();

        Ticket t = new Ticket();
        t.setNo(noGen.next("IT"));
        t.setTitle(req.getTitle());
        t.setDescription(req.getDescription());
        t.setType(type);
        t.setCat1(req.getCat1());
        t.setCat2(req.getCat2());
        if (("其他".equals(req.getCat1()) || "其他".equals(req.getCat2()))
                && req.getCategoryNote() != null && !req.getCategoryNote().isBlank())
            t.setCategoryNote(req.getCategoryNote().trim());
        t.setImpact(req.getImpact());
        t.setWorkImpact(req.getWorkImpact());
        t.setPriority(priority);
        t.setStatus(TicketStatus.NEW);
        t.setAssetNo(req.getAssetNo());
        t.setCreatorId(u.id());
        t.setCreatorName(u.name());
        t.setDeptName(u.deptName());
        t.setLocation(req.getLocation().trim());
        t.setSource(req.getSource() == null ? "钉钉工作台" : req.getSource());
        t.setKbHit(req.getKbHit());
        t.setTriedKb(req.getTriedKb() == null ? 0 : req.getTriedKb());
        t.setDueAt(now.plusHours(runtime.hoursOf(priority)));
        ticketMapper.insert(t);

        event(t.getNo(), null, TicketStatus.NEW, "CREATE", u.name(), "提交报修");
        notifyService.notifyPermission("ticket.accept", "新工单待受理",
                t.getNo() + " " + t.getTitle() + "（" + priority + "）", "TICKET", t.getNo());
        return fillRemain(ticketMapper.findByNo(t.getNo()));
    }

    // ---------------- 流转 ----------------

    @Transactional
    public Ticket accept(String no, RemarkReq req) {
        LoginUser u = CurrentUser.require();
        com.tzme.helpdesk.security.Permissions.require("ticket.accept");
        com.tzme.helpdesk.security.Permissions.require("ticket.handle");
        Ticket t = lockTicket(no);
        requireAnyStatus(t, TicketStatus.NEW, TicketStatus.ACCEPTED);
        if (t.getAssigneeId() != null && !u.id().equals(t.getAssigneeId()))
            throw new BizException(409, "工单已分配给其他工程师，请刷新页面");
        String from = t.getStatus();
        LocalDateTime now = LocalDateTime.now();
        t.setStatus(TicketStatus.IN_PROGRESS);
        t.setAssigneeId(u.id());
        t.setAssigneeName(u.name());
        if (t.getAcceptedAt() == null) t.setAcceptedAt(now);
        t.setAssignedAt(now);
        ticketMapper.update(t);
        String remark = req == null || req.getRemark() == null ? "" : req.getRemark().trim();
        event(no, from, t.getStatus(), "ACCEPT", u.name(), "受理并接单，处理工程师：" + u.name()
                + (remark.isBlank() ? "" : "；" + remark));
        notifyService.send(t.getCreatorName(), "工单已受理",
                no + " 已由工程师 " + u.name() + " 接单处理。", "TICKET", no);
        Ticket accepted = fillRemain(ticketMapper.findByNo(no));
        fillQueue(List.of(accepted));
        return accepted;
    }

    @Transactional
    public Ticket dispatch(String no, DispatchReq req) {
        LoginUser u = CurrentUser.require();
        Ticket t = lockTicket(no);
        requireAnyStatus(t, TicketStatus.ACCEPTED, TicketStatus.NEW);
        com.tzme.helpdesk.security.Permissions.require("ticket.dispatch");
        User eng = userMapper.findById(req.getAssigneeId());
        if (eng == null || !"ACTIVE".equals(eng.getStatus()) || !rolePermissions.resolve(eng).hasPermission("ticket.handle")) {
            throw new BizException(404, "工程师不存在");
        }
        String from = t.getStatus();
        t.setStatus(TicketStatus.IN_PROGRESS);
        t.setAssigneeId(eng.getId());
        t.setAssigneeName(eng.getName());
        t.setAssignedAt(LocalDateTime.now());
        if (t.getAcceptedAt() == null) {
            t.setAcceptedAt(LocalDateTime.now());
        }
        ticketMapper.update(t);
        event(no, from, t.getStatus(), "DISPATCH", u.name(), "派单给 " + eng.getName());
        notifyService.send(eng.getName(), "有新工单派给你",
                no + " " + t.getTitle() + "（" + t.getPriority() + "）", "TICKET", no);
        notifyService.send(t.getCreatorName(), "工单已派单",
                no + " 已安排工程师 " + eng.getName() + " 处理。", "TICKET", no);
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public Ticket addRecord(String no, RecordReq req) {
        LoginUser u = CurrentUser.require();
        Ticket t = requireTicket(no);
        requireStatus(t, TicketStatus.IN_PROGRESS);
        com.tzme.helpdesk.security.Permissions.require("ticket.handle");

        TicketRecord r = new TicketRecord();
        r.setTicketNo(no);
        r.setKind(req.getKind() == null ? "REMOTE" : req.getKind());
        r.setContent(req.getContent());
        r.setCostMin(req.getCostMin() == null ? 0 : req.getCostMin());
        r.setOperator(u.name());
        recordMapper.insert(r);
        event(no, t.getStatus(), t.getStatus(), "RECORD", u.name(), req.getContent());
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public Ticket finish(String no, FinishReq req) {
        LoginUser u = CurrentUser.require();
        com.tzme.helpdesk.security.Permissions.require("ticket.handle");
        management.lockSetting("management-lock");
        Ticket t = requireTicket(no);
        requireStatus(t, TicketStatus.IN_PROGRESS);

        // 关单时确认问题分类（不再维护解决代码）；分类必须属于该工单的问题类型
        var typeCategories = settings.get().getCategoriesByType().get(t.getType());
        List<String> cats = typeCategories == null ? null : typeCategories.get(req.getCat1());
        if (cats == null || (req.getCat2() != null && !req.getCat2().isBlank() && !cats.contains(req.getCat2())))
            throw new BizException("问题分类与当前问题类型不匹配，请重新选择");

        String from = t.getStatus();
        t.setStatus(TicketStatus.PENDING_CONFIRM);
        t.setCat1(req.getCat1());
        t.setCat2(req.getCat2());
        t.setSolutionNote(req.getNote());
        t.setFinishedAt(LocalDateTime.now());
        ticketMapper.update(t);
        event(no, from, t.getStatus(), "FINISH", u.name(), req.getCat1() + (req.getCat2() == null || req.getCat2().isBlank() ? "" : " · " + req.getCat2()) + "｜" + req.getNote());
        notifyService.send(t.getCreatorName(), "工单待确认",
                no + " 已处理完成：" + req.getNote() + "，请确认是否解决。", "TICKET", no);
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public Ticket confirm(String no, RateReq req) {
        LoginUser u = CurrentUser.require();
        Ticket t = lockTicket(no);
        requireStatus(t, TicketStatus.PENDING_CONFIRM);
        if (!t.getCreatorId().equals(u.id())) {
            throw new BizException(403, "只有报修人可以确认");
        }
        String from = t.getStatus();
        boolean solved = req.getSolved() == null || Boolean.TRUE.equals(req.getSolved());
        if (!solved) {
            t.setStatus(TicketStatus.IN_PROGRESS);
            ticketMapper.update(t);
            event(no, from, t.getStatus(), "CONFIRM", u.name(), "员工反馈未解决：" + safe(req.getRemark()));
            notifyService.send(safeName(t.getAssigneeName()), "员工反馈未解决",
                    no + " 员工反馈问题仍未解决，请继续跟进。", "TICKET", no);
            return ticketMapper.findByNo(no);
        }
        t.setStatus(TicketStatus.CLOSED);
        t.setClosedAt(LocalDateTime.now());
        if (req.getScore() != null) {
            t.setRating(req.getScore());
            t.setRatingTags(req.getTags());
        }
        ticketMapper.update(t);
        knowledge.captureClosedTicket(t);
        event(no, from, t.getStatus(), "RATE", u.name(), "确认解决并评价 " + safe(String.valueOf(req.getScore())) + " 星");
        notifyService.send(safeName(t.getAssigneeName()), "工单已关闭",
                no + " 员工已确认解决。", "TICKET", no);
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public KbArticle captureKnowledge(String no) {
        com.tzme.helpdesk.security.Permissions.require("kb.manage");
        com.tzme.helpdesk.security.Permissions.require("ticket.view.all");
        return knowledge.captureClosedTicket(lockTicket(no));
    }

    @Transactional
    public Ticket rate(String no, RateReq req) {
        LoginUser u = CurrentUser.require();
        Ticket t = requireTicket(no);
        if (!t.getCreatorId().equals(u.id())) {
            throw new BizException(403, "只有报修人可以评价");
        }
        if (t.getRating() != null) {
            throw new BizException("该工单已评价");
        }
        t.setRating(req.getScore());
        t.setRatingTags(req.getTags());
        ticketMapper.update(t);
        event(no, t.getStatus(), t.getStatus(), "RATE", u.name(), "评价 " + req.getScore() + " 星");
        if (req.getScore() != null && req.getScore() <= 3) {
            notifyService.notifyPermission("ticket.dispatch", "出现低分评价",
                    no + " 收到 " + req.getScore() + " 星评价，请安排回访。", "TICKET", no);
        }
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public Ticket reject(String no, RejectReq req) {
        LoginUser u = CurrentUser.require();
        Ticket t = lockTicket(no);
        requireStatus(t, TicketStatus.NEW);
        com.tzme.helpdesk.security.Permissions.require("ticket.accept");
        String from = t.getStatus();
        t.setStatus(TicketStatus.REJECTED);
        t.setSolutionNote(req.getReason());
        ticketMapper.update(t);
        event(no, from, t.getStatus(), "REJECT", u.name(), req.getReason());
        notifyService.send(t.getCreatorName(), "工单被驳回", no + " 驳回原因：" + req.getReason(), "TICKET", no);
        return ticketMapper.findByNo(no);
    }

    @Transactional
    public Ticket cancel(String no) {
        LoginUser u = CurrentUser.require();
        Ticket t = lockTicket(no);
        requireStatus(t, TicketStatus.NEW);
        if (!t.getCreatorId().equals(u.id())) {
            throw new BizException(403, "只有报修人可以撤销");
        }
        String from = t.getStatus();
        t.setStatus(TicketStatus.CANCELLED);
        ticketMapper.update(t);
        event(no, from, t.getStatus(), "CANCEL", u.name(), "报修人主动撤销");
        return ticketMapper.findByNo(no);
    }

    /** 定时任务：超时提醒（同一工单 4 小时内只提醒一次，靠通知去重做不到，这里只做扫描与提醒） */
    public int scanTimeout() {
        List<Ticket> list = ticketMapper.findTimeout(50);
        for (Ticket t : list) {
            notifyService.send(safeName(t.getAssigneeName()), "工单已超时",
                    t.getNo() + " 已超过解决时限，请尽快处理。", "SLA", t.getNo());
        }
        return list.size();
    }

    // ---------------- 内部 ----------------

    public Ticket requireTicket(String no) {
        Ticket t = ticketMapper.findByNo(no);
        if (t == null) {
            throw new BizException(404, "工单不存在：" + no);
        }
        return t;
    }

    private Ticket lockTicket(String no) {
        Ticket t = ticketMapper.findByNoForUpdate(no);
        if (t == null) throw new BizException(404, "工单不存在：" + no);
        return t;
    }

    private void requireStatus(Ticket t, String expected) {
        if (!expected.equals(t.getStatus())) {
            throw new BizException("当前状态（" + t.getStatus() + "）不允许该操作");
        }
    }

    private void requireAnyStatus(Ticket t, String... expected) {
        for (String s : expected) {
            if (s.equals(t.getStatus())) {
                return;
            }
        }
        throw new BizException("当前状态（" + t.getStatus() + "）不允许该操作");
    }

    private void requireAnyRole(LoginUser u, String... roles) {
        for (String r : roles) {
            if (u.hasRole(r)) {
                return;
            }
        }
        throw new BizException(403, "当前身份没有该操作权限");
    }

    private void event(String no, String from, String to, String action, String operator, String remark) {
        TicketEvent e = new TicketEvent();
        e.setTicketNo(no);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setAction(action);
        e.setOperator(operator);
        e.setRemark(remark);
        eventMapper.insert(e);
    }

    private void fillQueue(List<Ticket> tickets) {
        tickets.forEach(ticket -> ticket.setQueueAhead(null));
        List<String> nos = tickets.stream()
                .filter(ticket -> ticket.getAssigneeId() != null && TicketStatus.PROCESSING.contains(ticket.getStatus()))
                .map(Ticket::getNo).toList();
        if (nos.isEmpty()) return;
        var counts = new java.util.HashMap<String, Long>();
        ticketMapper.queueCounts(nos).forEach(row -> counts.put(row.getNo(), row.getQueueAhead()));
        tickets.forEach(ticket -> ticket.setQueueAhead(counts.get(ticket.getNo())));
    }

    private Ticket fillRemain(Ticket t) {
        if (t.getDueAt() != null && !TicketStatus.isEnded(t.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            long min = Duration.between(now, t.getDueAt()).toMinutes();
            t.setRemainMinutes(min);
            t.setOverdue(t.getDueAt().isBefore(now));
        } else {
            t.setOverdue(false);
        }
        return t;
    }

    private List<String> actionsOf(Ticket t, LoginUser u) {
        List<String> a = new ArrayList<>();
        boolean desk = u.hasPermission("ticket.accept");
        boolean eng = u.hasPermission("ticket.handle");
        boolean owner = t.getCreatorId() != null && t.getCreatorId().equals(u.id());
        String s = t.getStatus();
        if (TicketStatus.NEW.equals(s)) {
            if (desk) {
                if (eng && (t.getAssigneeId() == null || u.id().equals(t.getAssigneeId()))) a.add("ACCEPT");
                a.add("REJECT");
            }
            if (owner && u.hasPermission("ticket.confirm")) {
                a.add("CANCEL");
            }
        } else if (TicketStatus.ACCEPTED.equals(s)) {
            if (desk && eng && (t.getAssigneeId() == null || u.id().equals(t.getAssigneeId()))) {
                a.add("ACCEPT");
            } else if (u.hasPermission("ticket.dispatch")) {
                a.add("DISPATCH");
            }
        } else if (TicketStatus.IN_PROGRESS.equals(s)) {
            if (eng) {
                a.add("RECORD");
                a.add("FINISH");
            }
        } else if (TicketStatus.PENDING_CONFIRM.equals(s)) {
            if (owner && u.hasPermission("ticket.confirm")) {
                a.add("CONFIRM");
            }
        } else if (TicketStatus.CLOSED.equals(s)) {
            if (owner && t.getRating() == null && u.hasPermission("ticket.confirm")) {
                a.add("RATE");
            }
        }
        return a;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String safeName(String s) {
        return s == null || s.isBlank() ? "王敏" : s;
    }
}
