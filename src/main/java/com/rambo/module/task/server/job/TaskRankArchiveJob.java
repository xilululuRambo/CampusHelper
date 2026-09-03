package com.rambo.module.task.server.job;

import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.task.pojo.entity.RankTaskMonthly;
import com.rambo.module.task.server.service.RankTaskMonthlyService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * XXL-JOB：归档上月任务热门排名 Top10（每月 1 号执行）
 *
 * <p>RENAME 上月排行榜 KEY 为归档 KEY → 读取 Top10 入库 → 删除归档 KEY，新搜索自动创建当月 KEY。</p>
 *
 * <p>失败自愈设计：DB 入库失败时归档 KEY 保留不删，本次执行抛异常交由调度重试；
 * 重试（或下次调度）发现归档 KEY 已存在（孤儿）会先收编处理，避免孤儿 key
 * 被后续失败的 RENAME 覆盖导致数据永久丢失。</p>
 */
@Slf4j
@Component
public class TaskRankArchiveJob {

    @Resource
    private CacheClient cacheClient;
    @Resource
    private RankTaskMonthlyService rankTaskMonthlyService;

    @XxlJob("taskRankArchiveJob")
    public void execute() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = PrefixConstants.TASK_RANK_MONTH + lastMonth;
        String newKey = PrefixConstants.TASK_RANK_MONTH_ARCHIVE + lastMonth;

        // 0. 收编历史失败遗留的孤儿归档 key：上次 RENAME 后入库失败时当月 key 已不存在，
        //    若不在此处理，重试会在步骤 1 提前返回，孤儿 key 将永久滞留（且可能被下次失败覆盖）
        if (cacheClient.hasKey(newKey)) {
            log.warn("检测到遗留归档 key（上次入库失败的残留），先收编处理：{}", lastMonth);
            doArchive(newKey, lastMonth);
        }

        // 1. 上月无任何上榜记录（key 不存在）时直接跳过：
        //    Redis RENAME 对不存在的 key 会抛异常，导致 Job 空跑失败
        if (Boolean.FALSE.equals(cacheClient.hasKey(key))) {
            log.info("任务排行榜归档跳过：上月 {} 无数据", lastMonth);
            return;
        }

        // 2. RENAME 为归档 key（原子操作，当月 KEY 自动空缺）
        cacheClient.rename(key, newKey);

        // 3. 入库（失败时归档 key 保留，由下次调度的步骤 0 收编重试）
        doArchive(newKey, lastMonth);
    }

    /**
     * 归档 key → MySQL（含幂等防重），入库成功后才删除归档 key。
     *
     * @param newKey    归档 key（TASK_RANK_MONTH_ARCHIVE + 月份）
     * @param lastMonth 归档归属月份（上月）
     */
    private void doArchive(String newKey, String lastMonth) {
        // 读取归档 Top10（有序 List，保持排名顺序）
        List<CacheClient.ZSetEntry> topUsers =
                cacheClient.zReverseRangeWithScores(newKey, 0, NumConstants.TOP_RANK_END_INDEX);
        if (topUsers.isEmpty()) {
            // 归档 key 存在但为空（如 RENAME 了空 ZSet）：清理残留后安全返回
            cacheClient.delete(newKey);
            return;
        }

        List<RankTaskMonthly> list = new ArrayList<>();
        int rank = 1;
        for (CacheClient.ZSetEntry entry : topUsers) {
            RankTaskMonthly entity = new RankTaskMonthly();
            entity.setUserId(Long.valueOf(Objects.requireNonNull(entry.value())));
            entity.setFinishCount((int) entry.score());
            entity.setRankNum(rank++);
            entity.setMonth(lastMonth);
            list.add(entity);
        }

        // 幂等防重：过滤该月已归档用户（上次已入库但删 key 前进程崩溃的极端场景）。
        // 表存在 uk_user_month 唯一键，直接重复插入会撞键抛异常导致收编永远失败；
        // 过滤后仅补插缺失用户，部分入库场景也能自愈
        List<Long> existingUserIds = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, lastMonth)
                .list().stream()
                .map(RankTaskMonthly::getUserId)
                .toList();
        if (!existingUserIds.isEmpty()) {
            list.removeIf(e -> existingUserIds.contains(e.getUserId()));
            if (list.isEmpty()) {
                log.warn("任务排行榜 {} 全部用户已归档，跳过重复入库", lastMonth);
                cacheClient.delete(newKey);
                return;
            }
            log.warn("任务排行榜 {} 已有 {} 条归档记录，过滤后剩余 {} 条待补插",
                    lastMonth, existingUserIds.size(), list.size());
        }

        // 入库：失败抛出异常标记本次调度失败（归档 key 保留，等下次收编）
        try {
            rankTaskMonthlyService.saveBatch(list);
        } catch (Exception e) {
            log.error("任务排行榜归档入库失败，归档 key 保留待下次调度收编：{}", lastMonth, e);
            throw e;
        }

        // 入库成功后才删除归档 key
        cacheClient.delete(newKey);

        XxlJobHelper.handleSuccess("归档完成，共 " + list.size() + " 条");
    }
}
