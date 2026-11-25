package com.tzme.helpdesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.PersonSearch;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.IncomingNotificationRules;
import com.tzme.helpdesk.dto.IncomingNotificationRules.TicketRule;
import com.tzme.helpdesk.mapper.ManagementMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.Permissions;
import com.tzme.helpdesk.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IncomingNotificationService {
    private static final String KEY = "incoming-notifications";
    private static final Set<String> ASSET_TYPES = Set.of("RECEIVE", "BORROW", "REPLACE", "RETURN");
    private final ManagementMapper management;
    private final UserMapper users;
    private final RuntimeSettingsService settings;
    private final AuditService audit;
    private final NotifyService notify;
    private final ObjectMapper json;

    public record Recipient(Long id, String name, String department, String employeeNo, String role,
                            List<List<String>> namePinyin) { }
    public record Config(IncomingNotificationRules rules, Map<String, Map<String, List<String>>> categories,
                         List<Recipient> recipients) { }
    private record CategoryKey(String type, String cat1, String cat2) { }

    public Config config() {
        Permissions.require("settings.manage");
        List<Recipient> recipients = eligibleRecipients();
        var rules = retainEligible(read(management.setting(KEY)), idsOf(recipients));
        return new Config(rules, settings.get().getCategoriesByType(), recipients);
    }

    @Transactional
    public IncomingNotificationRules save(IncomingNotificationRules input) {
        Permissions.require("settings.manage");
        management.createSetting(KEY, write(IncomingNotificationRules.defaults()));
        IncomingNotificationRules previous = read(management.lockSetting(KEY));
        if (!previous.revision().equals(input.revision())) {
            throw new BizException(409, "通知配置已被修改，请重新加载后再保存");
        }

        Set<Long> eligible = idsOf(eligibleRecipients());
        var previousEligible = retainEligible(previous, eligible);
        var categories = settings.get().getCategoriesByType();
        Set<CategoryKey> seen = new HashSet<>();
        List<TicketRule> tickets = new ArrayList<>();
        for (TicketRule rule : input.ticketRules()) {
            CategoryKey key = key(rule);
            List<Long> recipients = validateRecipients(rule.recipientIds(), eligible);
            var children = categories.getOrDefault(key.type(), Map.of()).get(key.cat1());
            if (children == null || (key.cat2() != null && !children.contains(key.cat2()))) {
                // 保留已保存的旧分类，允许管理员删除，避免分类调整后阻塞其他规则的保存。
                boolean unchanged = previousEligible.ticketRules().stream().anyMatch(old ->
                        key(old).equals(key) && old.recipientIds().equals(recipients));
                if (!unchanged) throw new BizException("报修分类已变更，请重新选择");
            }
            if (!seen.add(key)) throw new BizException("同一报修分类只能设置一条通知规则");
            tickets.add(new TicketRule(key.type(), key.cat1(), key.cat2(), recipients));
        }
        Map<String, List<Long>> assets = new LinkedHashMap<>();
        for (var entry : input.assetRecipients().entrySet()) {
            if (!ASSET_TYPES.contains(entry.getKey())) throw new BizException("不支持的资产通知类型");
            assets.put(entry.getKey(), validateRecipients(entry.getValue(), eligible));
        }

        var saved = new IncomingNotificationRules(input.revision() + 1, tickets, assets);
        String value = write(saved);
        if (value.getBytes(StandardCharsets.UTF_8).length > 60_000) throw new BizException("通知规则过多，请减少后保存");
        management.updateSetting(KEY, value);
        audit.record("INCOMING_NOTIFICATION_UPDATE", KEY,
                "更新来单通知接收人，报修规则 " + tickets.size() + " 条，资产规则 " + assets.size() + " 条");
        return saved;
    }

    public void newTicket(Ticket ticket) {
        toTicketDesk(ticket, NotifyService.NEW_TICKET, "收到新的报修工单 " + ticket.getNo(), "TICKET");
    }

    public void remindUnassignedTicket(Ticket ticket, String title) {
        boolean response = ticket.getAcceptedAt() == null && ticket.getResponseDueAt() != null
                && (ticket.getDueAt() == null || !ticket.getResponseDueAt().isAfter(ticket.getDueAt()));
        String target = response ? "首次响应" : "解决";
        toTicketDesk(ticket, title, ticket.getNo() + " 已超过" + target + "时限，请尽快受理。", "SLA");
    }

    private void toTicketDesk(Ticket ticket, String title, String content, String kind) {
        var rules = read(management.setting(KEY)).ticketRules();
        List<Long> exact = List.of(), parent = List.of();
        for (TicketRule rule : rules) {
            if (!rule.type().equals(ticket.getType()) || !rule.cat1().equals(ticket.getCat1())) continue;
            if (rule.cat2() == null) parent = rule.recipientIds();
            else if (rule.cat2().equals(ticket.getCat2())) exact = rule.recipientIds();
        }
        if (sendTo(exact, title, content, kind, ticket.getNo())
                || sendTo(parent, title, content, kind, ticket.getNo())) return;
        notify.notifyIncoming("ticket.accept", title,
                "TICKET".equals(kind) ? ticket.getNo() + " " + ticket.getTitle() + "（" + ticket.getPriority() + "）" : content,
                kind, ticket.getNo());
    }

    public void newAssetRequest(String type, String label, String no, String assetName, String applicant, boolean dingStarted) {
        var ids = read(management.setting(KEY)).assetRecipients().getOrDefault(type, List.of());
        String title = NotifyService.NEW_ASSET;
        String channel = dingStarted ? "已进入钉钉审批" : "等待系统内审批";
        String content = no + " " + label + " · " + channel;
        if (sendTo(ids, title, content, "ASSET", no) || dingStarted) return;
        notify.notifyIncoming("request.approve", title, no + " " + label + " · " + assetName
                + "（" + applicant + "）", "ASSET", no);
    }

    private boolean sendTo(List<Long> ids, String title, String content, String kind, String no) {
        var recipients = new ArrayList<User>();
        var dingUserIds = new HashSet<String>();
        for (Long id : ids) {
            User user = users.findById(id);
            if (canReceive(user) && dingUserIds.add(user.getDingUserId())) recipients.add(user);
        }
        if (recipients.isEmpty()) return false;
        notify.sendToAccounts(recipients, title, content, kind, no);
        return true;
    }

    private boolean canReceive(User user) {
        return user != null && "ACTIVE".equals(user.getStatus())
                && Roles.isItOrAdmin(user.getRole())
                && user.getDingUserId() != null && !user.getDingUserId().isBlank();
    }

    private List<Recipient> eligibleRecipients() {
        return users.listActiveWithDing().stream().filter(this::canReceive)
                .map(user -> new Recipient(user.getId(), user.getName(), user.getDeptName(), user.getEmployeeNo(),
                        Roles.normalize(user.getRole()), PersonSearch.namePinyin(user.getName())))
                .sorted(Comparator.comparing(Recipient::name).thenComparing(Recipient::id))
                .toList();
    }

    private static Set<Long> idsOf(List<Recipient> recipients) {
        return recipients.stream().map(Recipient::id).collect(Collectors.toSet());
    }

    private static IncomingNotificationRules retainEligible(IncomingNotificationRules rules, Set<Long> eligible) {
        List<TicketRule> tickets = rules.ticketRules().stream()
                .map(rule -> new TicketRule(rule.type(), rule.cat1(), rule.cat2(),
                        rule.recipientIds().stream().filter(eligible::contains).toList()))
                .filter(rule -> !rule.recipientIds().isEmpty())
                .toList();
        Map<String, List<Long>> assets = new LinkedHashMap<>();
        rules.assetRecipients().forEach((type, ids) -> {
            List<Long> recipients = ids.stream().filter(eligible::contains).toList();
            if (!recipients.isEmpty()) assets.put(type, recipients);
        });
        return new IncomingNotificationRules(rules.revision(), tickets, assets);
    }

    private static CategoryKey key(TicketRule rule) {
        String child = rule.cat2();
        return new CategoryKey(rule.type(), rule.cat1(), child == null || child.isBlank() ? null : child);
    }

    private static List<Long> validateRecipients(List<Long> ids, Set<Long> eligible) {
        if (!eligible.containsAll(ids))
            throw new BizException("通知接收人须为在职且已绑定钉钉的 IT 或管理员，请重新选择");
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private IncomingNotificationRules read(String value) {
        if (value == null) return IncomingNotificationRules.defaults();
        try {
            return json.readValue(value, IncomingNotificationRules.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("读取来单通知配置失败", exception);
        }
    }

    private String write(IncomingNotificationRules value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存来单通知配置失败", exception);
        }
    }
}
