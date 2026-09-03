package com.rambo.infrastructure.cache;

import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁门面：业务层唯一的加锁/解锁入口。
 * <p>
 * 屏蔽 Redisson RLock 细节，调用方无需关心锁实例获取、持有者校验。
 * 底层依赖 Redisson（支持看门狗自动续期、可重入），key 即锁标识。
 * </p>
 */
@Component
public class LockClient {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 尝试加锁，等待 waitTime 内未获取则返回 false。
     * <p>不指定持有时间：锁由 Redisson 看门狗自动续期（默认 30s 续期周期），
     * 适合业务耗时不可预估的临界区，unlock 时自动释放。</p>
     */
    public boolean tryLock(String key, long waitTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 尝试加锁：等待 waitTime 内未获取则返回 false；获取后持有至多 holdTime 自动释放。
     * <p>指定持有时间：锁在 holdTime 后强制过期（防死锁），
     * 适合业务耗时已知且短于 holdTime 的临界区；业务提前结束时仍需 unlock。</p>
     */
    public boolean tryLock(String key, long waitTime, long holdTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(waitTime, holdTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁：仅当当前线程持有该锁时才真正解锁（幂等，可安全放入 finally）。
     */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
