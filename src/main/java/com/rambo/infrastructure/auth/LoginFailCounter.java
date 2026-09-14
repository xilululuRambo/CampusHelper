package com.rambo.infrastructure.auth;

import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 登录失败计数器（管理员密码登录与用户验证码登录共用同一套失败计数机制）。
 *
 * <p>固定窗口计数：首次失败时设置窗口过期时间，窗口内累计递增。
 * 达到阈值后的处置策略由调用方按场景决定——管理员密码登录锁定账号（15 分钟），
 * 用户验证码登录作废当前验证码（需重新获取后才能继续尝试）；
 * 计数机制一致、处置策略各异，避免两处登录各自维护一份实现（曾出现管理员有计数、
 * 验证码登录无计数的不一致）。</p>
 */
@Component
public class LoginFailCounter {

    @Resource
    private CacheClient cacheClient;

    /**
     * 记录一次登录失败并返回窗口内累计次数；首次记录时设置窗口过期时间。
     *
     * @param failKey       失败计数 key
     * @param windowMinutes 计数窗口时长（分钟，自首次失败起算）
     * @return 窗口内累计失败次数（Redis 异常返回 null 时按 0 处理，不额外放大失败）
     */
    public long record(String failKey, int windowMinutes) {
        Long fails = cacheClient.increment(failKey);
        if (fails == null) {
            return 0L;
        }
        if (fails == 1L) {
            cacheClient.expire(failKey, windowMinutes, TimeUnit.MINUTES);
        }
        return fails;
    }

    /**
     * 读取窗口内累计失败次数（无记录按 0 处理）。
     */
    public long count(String failKey) {
        String value = cacheClient.get(failKey);
        return value == null ? 0L : Long.parseLong(value);
    }

    /**
     * 清除失败计数（登录成功、重新签发验证码时调用，开启新一轮计数）。
     */
    public void clear(String failKey) {
        cacheClient.delete(failKey);
    }
}
