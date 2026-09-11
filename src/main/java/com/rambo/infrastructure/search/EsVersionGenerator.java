package com.rambo.infrastructure.search;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ES 外部版本号生成器：进程内严格单调递增。
 *
 * <p>以 epoch 毫秒为基准，叠加进程内自增序列，解决两个问题：</p>
 * <ul>
 *   <li>同一毫秒内的多次写入也能拿到严格递增的版本号（不被时间戳粒度抹平）；</li>
 *   <li>系统时钟回拨（NTP 校时）时，返回值仍不低于上一次，避免生成「更小版本」导致旧事件
 *       反而覆盖新数据。</li>
 * </ul>
 *
 * <p>版本号作为 ES 的 external version：ES 只接受比文档当前版本更大的写入，
 * 旧事件后到时会被 ES 以 409 版本冲突拒绝，从根上杜绝「旧数据覆盖新数据」。</p>
 */
@Component
public class EsVersionGenerator {

    private final AtomicLong last = new AtomicLong(0L);

    /**
     * 生成下一个严格递增的版本号。
     *
     * @return 单调递增的版本号（epoch 毫秒为基准）
     */
    public long next() {
        return last.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
    }
}
