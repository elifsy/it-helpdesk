package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.mapper.SeqMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 单号生成。
 * 优先 Redis 按天自增（多实例安全）；未启用 Redis 时退回「数据库当天最大号 + 1」，
 * 保证本地单机也能得到 IT-20261109-0001 这种连续编号；两者都失败才用时间戳兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoGen {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final HelpdeskProperties props;
    private final SeqMapper seqMapper;

    /** 调用方持有台账写锁，按已保存的同日资产编号分配下一个序号。 */
    public String nextAsset() {
        String day = LocalDate.now().format(DAY);
        String max = seqMapper.maxAssetNo(day);
        int next = max == null ? 1 : Integer.parseInt(max.substring(max.lastIndexOf('-') + 1)) + 1;
        if (next > 99999) throw new BizException(409, "今日资产编号已用完");
        return "ZC-" + day + "-" + String.format(Locale.ROOT, "%05d", next);
    }

    /** 单机取号串行化，避免并发拿到同一个号撞唯一索引 */
    public synchronized String next(String prefix) {
        String day = LocalDate.now().format(DAY);
        if (!props.isRedisEnabled()) {
            return prefix + "-" + day + "-" + String.format("%04d", nextFromDb(prefix, day));
        }
        String key = "helpdesk:seq:" + prefix + ":" + day;
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redis.expire(key, Duration.ofDays(2));
            }
            return prefix + "-" + day + "-" + String.format("%04d", n == null ? 1 : n);
        } catch (Exception e) {
            log.warn("redis seq unavailable, fallback to db: {}", e.getMessage());
            return prefix + "-" + day + "-" + String.format("%04d", nextFromDb(prefix, day));
        }
    }

    private int nextFromDb(String prefix, String day) {
        try {
            String max = switch (prefix) {
                case "IT" -> seqMapper.maxTicketNo(day);
                case "PR" -> seqMapper.maxPurchaseNo(day);
                default -> seqMapper.maxAssetRequestNo(day);
            };
            if (max == null || max.length() < 4) {
                return 1;
            }
            return Integer.parseInt(max.substring(max.length() - 4)) + 1;
        } catch (Exception e) {
            log.warn("db seq failed, fallback to timestamp: {}", e.getMessage());
            return (int) (System.currentTimeMillis() % 10000);
        }
    }
}
