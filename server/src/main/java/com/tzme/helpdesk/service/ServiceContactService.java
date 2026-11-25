package com.tzme.helpdesk.service;

import com.tzme.helpdesk.domain.User;
import com.tzme.helpdesk.dto.DutySettings;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.service.DingTalkService.LeavePeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ServiceContactService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration CACHE_TIME = Duration.ofMinutes(5);

    private final RuntimeSettingsService settings;
    private final UserMapper users;
    private final DingTalkService dingTalk;
    private Snapshot cached;

    public record Contact(DutySettings.Member member, String status, LocalDateTime leaveStart, LocalDateTime leaveEnd) { }
    public record Person(Long id, String name, String department, String employeeNo) { }
    public record Roster(List<Contact> contacts, String hotline, String hotlineNote) { }
    private record Snapshot(List<String> ids, LocalDate day, Instant expires, Map<String, List<LeavePeriod>> periods) { }

    public Roster roster() {
        var duty = settings.get().getDuty();
        var active = duty.members().isEmpty() ? List.<User>of() : users.listActive();
        var byId = active.stream().collect(Collectors.toMap(User::getId, user -> user));
        var byName = active.stream().collect(Collectors.groupingBy(User::getName));
        List<String> ids = duty.members().stream().map(member -> resolve(member, byId, byName)).toList();
        List<String> distinct = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        Instant now = Instant.now();
        LocalDate day = LocalDate.ofInstant(now, ZONE);
        Snapshot snapshot = distinct.isEmpty() || !dingTalk.enabled() ? null : snapshot(distinct, day, now);
        List<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < duty.members().size(); i++) {
            var member = duty.members().get(i);
            String id = ids.get(i);
            contacts.add(status(member, id, snapshot, now));
        }
        return new Roster(contacts, duty.hotline(), duty.hotlineNote());
    }

    public Roster displayRoster() {
        var roster = roster();
        var contacts = roster.contacts().stream()
                .map(contact -> new Contact(
                        new DutySettings.Member(contact.member().position(), contact.member().name()),
                        contact.status(), null, null))
                .toList();
        return new Roster(contacts, roster.hotline(), roster.hotlineNote());
    }

    public List<Person> search(String keyword) {
        return users.searchActivePeople(keyword, true).stream()
                .map(user -> new Person(user.getId(), user.getName(), user.getDeptName(), user.getEmployeeNo()))
                .toList();
    }

    private static String resolve(DutySettings.Member member, Map<Long, User> byId, Map<String, List<User>> byName) {
        var matching = byName.getOrDefault(member.name().strip(), List.of());
        User user = member.userId() != null ? byId.get(member.userId())
                : matching.size() == 1 ? matching.get(0) : null;
        return user != null && hasDingTalk(user) ? user.getDingUserId() : null;
    }

    private static boolean hasDingTalk(User user) {
        return user.getDingUserId() != null && !user.getDingUserId().isBlank();
    }

    private synchronized Snapshot snapshot(List<String> ids, LocalDate day, Instant now) {
        if (cached != null && cached.ids().equals(ids) && cached.day().equals(day) && now.isBefore(cached.expires()))
            return cached;
        Instant start = day.atStartOfDay(ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        Map<String, List<LeavePeriod>> periods;
        try {
            periods = dingTalk.leavePeriods(ids, start, end.minusMillis(1)).stream()
                    .filter(period -> period.start().isBefore(end) && period.end().isAfter(start))
                    .collect(Collectors.groupingBy(LeavePeriod::userId));
        } catch (RuntimeException unavailable) {
            periods = null;
        }
        cached = new Snapshot(ids, day, now.plus(CACHE_TIME), periods);
        return cached;
    }

    private static Contact status(DutySettings.Member member, String id, Snapshot snapshot, Instant now) {
        if (id == null) return new Contact(member, "UNLINKED", null, null);
        if (snapshot == null || snapshot.periods() == null) return new Contact(member, "UNKNOWN", null, null);
        var periods = snapshot.periods().getOrDefault(id, List.of());
        if (periods.isEmpty()) return new Contact(member, "NONE", null, null);
        var current = periods.stream().filter(period -> !now.isBefore(period.start()) && now.isBefore(period.end()))
                .max(Comparator.comparing(LeavePeriod::end));
        var display = current.orElseGet(() -> periods.stream().filter(period -> now.isBefore(period.start()))
                .min(Comparator.comparing(LeavePeriod::start))
                .orElseGet(() -> Collections.max(periods, Comparator.comparing(LeavePeriod::end))));
        return new Contact(member, current.isPresent() ? "ON_LEAVE" : "TODAY",
                LocalDateTime.ofInstant(display.start(), ZONE), LocalDateTime.ofInstant(display.end(), ZONE));
    }
}
