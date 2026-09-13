package com.rambo.infrastructure.cache;

import jakarta.annotation.Resource;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存门面：业务层唯一的 Redis 读写入口。
 * <p>
 * 职责：屏蔽底层客户端差异（StringRedisTemplate / Redisson），
 * 向业务层提供与"缓存/会话"语义对齐的 API，业务代码不再感知 Redis 客户端类型。
 * </p>
 * <p>
 * 使用约定：
 * <ul>
 *   <li>String / Key / ZSet 操作基于 StringRedisTemplate（String 序列化）；</li>
 *   <li>map* 系列基于 Redisson RMapCache，支持 <b>field 级独立过期</b>，
 *       与 StringRedisTemplate 写入的 Hash 编码不兼容，同一 key 请勿混用两种写入方式。</li>
 * </ul>
 * </p>
 */
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * ZSet 倒序读取结果条目（业务语义：成员 + 分数），避免业务层依赖 Spring Data 类型。
     */
    public record ZSetEntry(String value, double score) {
    }

    // ==================== String 操作 ====================

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * key 不存在时写入（原子操作），用于冷却、限流等"首次放行"场景。
     */
    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
    }

    public Long increment(String key) {
        return stringRedisTemplate.opsForValue().increment(key);
    }

    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    // ==================== Key 操作 ====================

    public Boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    public Boolean delete(String key) {
        return stringRedisTemplate.delete(key);
    }

    /**
     * 原子重命名（RENAME），用于"当天 key → 归档 key"等滚动场景。
     */
    public void rename(String key, String newKey) {
        stringRedisTemplate.rename(key, newKey);
    }

    /**
     * 按 pattern 扫描 key（SCAN 游标非阻塞，避免 KEYS 命令阻塞 Redis）。
     * 仅用于低频 Job 场景（如归档 Job 收编孤儿 key），业务请求路径禁止使用。
     *
     * @param pattern key 模式，如 {@code hot:keywords:archive:*}
     * @return 匹配的 key 集合
     */
    public Set<String> scanKeys(String pattern) {
        return stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(stringRedisTemplate.getStringSerializer().deserialize(cursor.next()));
                }
            }
            return keys;
        });
    }

    public void expire(String key, long timeout, TimeUnit unit) {
        stringRedisTemplate.expire(key, timeout, unit);
    }

    /**
     * 读取 key 的剩余过期时间（毫秒）；key 不存在返回 -2、未设置过期返回 -1（Redis TTL 语义）。
     * 供「窗口过半才续期」的阈值节流使用：把每请求的 TTL 重写降为每半窗口最多一次。
     */
    public long getRemainTtl(String key) {
        Long remainMs = stringRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return remainMs != null ? remainMs : -2;
    }

    // ==================== ZSet 操作（排行榜 / 热搜） ====================

    public void zIncrementScore(String key, String member, double delta) {
        stringRedisTemplate.opsForZSet().incrementScore(key, member, delta);
    }

    /**
     * 倒序取分数区间（分从大到小），返回业务层可读的条目。
     *
     * @param start 起始排名（含），0 表示第一名
     * @param end   结束排名（含），-1 表示全部
     */
    public List<ZSetEntry> zReverseRangeWithScores(String key, long start, long end) {
        // 必须返回有序 List：reverseRangeWithScores 返回的 TypedTuple 本身是 LinkedHashSet（有序），
        // 若用 Collectors.toSet() 收集为 HashSet，迭代顺序无保证 → 排行榜/热搜 Top1-10 顺序随机错乱
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> range =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (range == null || range.isEmpty()) {
            return List.of();
        }
        return range.stream()
                .map(t -> new ZSetEntry(t.getValue(), t.getScore() != null ? t.getScore() : 0D))
                .toList();
    }

    // ==================== Map 操作（Redisson RMapCache，field 级 TTL） ====================

    public String mapGet(String key, String field) {
        RMapCache<String, String> map = redissonClient.getMapCache(key);
        return map.get(field);
    }

    /**
     * 读取 Map 字段的剩余过期时间（毫秒）；字段不存在返回 -2（Redis TTL 语义）。
     * 供「窗口过半才续期」的阈值节流使用：把每请求的带 TTL 重写降为每半窗口最多一次。
     */
    public long mapRemainTtl(String key, String field) {
        RMapCache<String, String> map = redissonClient.getMapCache(key);
        return map.remainTimeToLive(field);
    }

    /**
     * 仅重置 Map 字段的 field 级过期时间，<b>不重写 value</b>（Redisson 3.23+ expireEntry）。
     * <p>
     * 底层为单条 Lua 原子脚本：只更新超时记账，不读也不写 value，
     * 避免「mapGet 读值 + mapPut 写回」读改写方案的竞态窗口；
     * 字段不存在或已过期时返回 false，不会复活已结束的会话。
     * </p>
     * 注意：ttl 必须为正数——expireEntry 会把 0/负数解释为「移除 TTL（永不过期）」，故此处显式拒绝。
     *
     * @return true=续期成功；false=字段不存在或已过期
     */
    public boolean mapExpireEntry(String key, String field, long ttl, TimeUnit unit) {
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl 必须为正数（0/负数会被 expireEntry 解释为永不过期）");
        }
        RMapCache<String, String> map = redissonClient.getMapCache(key);
        return map.expireEntry(field, Duration.ofMillis(unit.toMillis(ttl)), null);
    }

    /**
     * 写入 Map 字段并指定 <b>field 级过期时间</b>（多设备会话、各自独立续期场景）。
     */
    public void mapPut(String key, String field, String value, long ttl, TimeUnit unit) {
        RMapCache<String, String> map = redissonClient.getMapCache(key);
        map.put(field, value, ttl, unit);
    }

    /**
     * 删除 Map 中的单个字段（必须经 RMapCache 删除，StringRedisTemplate 与 Redisson codec 不兼容）。
     */
    public void mapRemove(String key, String field) {
        RMapCache<String, String> map = redissonClient.getMapCache(key);
        map.fastRemove(field);
    }
}
