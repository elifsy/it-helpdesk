package com.tzme.helpdesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 统计口径与需求蓝图 7.1 一致；看板数据走 Redis 缓存 60 秒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final TicketMapper ticketMapper;
    private final AssetMapper assetMapper;
    private final AssetRequestMapper requestMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final HelpdeskProperties props;
    private final RuntimeSettingsService settings;

    public Map<String, Object> overview() {
        return cached("overview", settings.get().getStatsCacheSeconds(), () -> {
            Map<String, Object> m = ticketMapper.overview();
            long slaOk = num(m.get("slaOk"));
            long slaTotal = num(m.get("slaTotal"));
            m.put("slaRate", slaTotal == 0 ? 0.0 : Math.round(slaOk * 1000.0 / slaTotal) / 10.0);
            long responseTotal = num(m.get("responseSlaTotal"));
            m.put("responseSlaRate", responseTotal == 0 ? 0.0 : Math.round(num(m.get("responseSlaOk")) * 1000.0 / responseTotal) / 10.0);
            long avgMin = num(m.get("avgMinutes"));
            m.put("avgHours", Math.round(avgMin / 6.0) / 10.0);
            long triedKb = num(m.get("triedKbCount"));
            long total = num(m.get("total"));
            m.put("kbTryRate", total == 0 ? 0.0 : Math.round(triedKb * 1000.0 / total) / 10.0);
            m.put("assetTotal", assetMapper.countAll());
            m.put("assetIdle", assetMapper.countByStatus("闲置"));
            m.put("assetInUse", assetMapper.countByStatus("在用"));
            m.put("assetBorrowed", assetMapper.countByStatus("借出中"));
            m.put("pendingApprovals", requestMapper.countPending());
            return m;
        });
    }

    public List<Map<String, Object>> trend(int days) {
        var rows = ticketMapper.trend(since(days));
        rows.forEach(row -> {
            String day=String.valueOf(row.remove("day"));
            row.put("date",day.length()>=10 ? day.substring(5,10) : day);
        });
        return rows;
    }

    public List<Map<String, Object>> category(int days) {
        return ticketMapper.categoryDist(since(days));
    }

    public List<Map<String, Object>> engineers(int days) {
        return ticketMapper.engineerRank(since(days));
    }

    private java.time.LocalDateTime since(int days) {
        return java.time.LocalDate.now().minusDays(Math.max(1,Math.min(90,days))).atStartOfDay();
    }

    public List<Map<String, Object>> problemAssets(int limit) {
        return ticketMapper.problemAssets(Math.max(1, Math.min(20, limit)));
    }

    private Map<String, Object> cached(String key, int seconds, Supplier<Map<String, Object>> supplier) {
        if (!props.isRedisEnabled() || seconds == 0) {
            return supplier.get();
        }
        String cacheKey = "helpdesk:stats:" + key + ":" + seconds;
        try {
            String json = redis.opsForValue().get(cacheKey);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, Map.class);
            }
        } catch (Exception e) {
            log.warn("read stats cache failed: {}", e.getMessage());
        }
        Map<String, Object> data = supplier.get();
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(data), Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.warn("write stats cache failed: {}", e.getMessage());
        }
        return data;
    }

    private long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
