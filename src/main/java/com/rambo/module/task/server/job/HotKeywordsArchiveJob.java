package com.rambo.module.task.server.job;

import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.task.pojo.entity.HotKeywords;
import com.rambo.module.task.server.service.HotKeywordsService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * XXL-JOB：归档昨日热搜关键词到数据库（每天 0 点 5 分执行）
 *
 * <p>RENAME 当天固定 KEY 为带日期后缀的归档 KEY（{@code hot:keywords:archive:yyyy-MM-dd}），
 * 入库后删除归档 KEY，新搜索自动创建当天 KEY。</p>
 *
 * <p>失败自愈设计：DB 入库失败时归档 KEY 保留不删，本次执行抛异常交由调度重试；
 * 重试（或下次调度）先扫描收编全部遗留归档 KEY（孤儿），并从 KEY 解析数据真实归属日期，
 * 避免孤儿 key 被后续失败的 RENAME 覆盖导致数据永久丢失、跨天重试记错日期。</p>
 */
@Slf4j
@Component
public class HotKeywordsArchiveJob {

    private static final DateTimeFormatter ARCHIVE_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Resource
    private CacheClient cacheClient;
    @Resource
    private HotKeywordsService hotKeywordsService;

    @XxlJob("hotKeywordsArchiveJob")
    public void execute() {
        LocalDate recordDate = LocalDate.now().minusDays(1);

        // 0. 收编历史失败遗留的孤儿归档 key：归档 key 带日期后缀（RENAME 后入库失败时
        //    当天 key 已不存在），若不先收编，重试会在步骤 1 提前返回，孤儿 key 将永久滞留
        //    （且可能被下次失败覆盖）；收编日期从 key 解析，跨天重试也能还原真实归属日期
        Set<String> orphanKeys = cacheClient.scanKeys(PrefixConstants.HOT_KEYWORDS_ARCHIVE + "*");
        for (String archiveKey : orphanKeys) {
            LocalDate orphanDate = parseArchiveDate(archiveKey);
            log.warn("检测到遗留归档 key（上次入库失败的残留），先收编处理：{}", orphanDate);
            doArchive(archiveKey, orphanDate);
        }

        // 1. 昨天没有搜索，直接返回
        if (!cacheClient.hasKey(PrefixConstants.HOT_KEYWORDS)) {
            return;
        }

        // 2. RENAME 为归档 key（原子操作，当天 KEY 自动空缺）
        String archiveKey = PrefixConstants.HOT_KEYWORDS_ARCHIVE + recordDate.format(ARCHIVE_DATE_FMT);
        cacheClient.rename(PrefixConstants.HOT_KEYWORDS, archiveKey);

        // 3. 入库（失败时归档 key 保留，由下次调度的步骤 0 收编重试）
        doArchive(archiveKey, recordDate);
    }

    /**
     * 从归档 key 解析数据归属日期。
     * <p>收编孤儿时不能用"本次调度的昨天"，否则跨天重试会把上次失败的数据
     * 记到错误日期，还会让当天真实数据被幂等误判丢弃。</p>
     *
     * @param archiveKey 归档 key（HOT_KEYWORDS_ARCHIVE + yyyy-MM-dd）
     * @return 数据归属日期
     */
    private LocalDate parseArchiveDate(String archiveKey) {
        return LocalDate.parse(archiveKey.substring(PrefixConstants.HOT_KEYWORDS_ARCHIVE.length()));
    }

    /**
     * 归档 key → MySQL（含幂等防重），入库成功后才删除归档 key。
     *
     * @param archiveKey 归档 key（HOT_KEYWORDS_ARCHIVE + yyyy-MM-dd）
     * @param recordDate 热搜归属日期（与归档 key 后缀一致）
     */
    private void doArchive(String archiveKey, LocalDate recordDate) {
        List<CacheClient.ZSetEntry> top =
                cacheClient.zReverseRangeWithScores(archiveKey, 0, NumConstants.TOP_RANK_END_INDEX);
        if (top.isEmpty()) {
            cacheClient.delete(archiveKey);
            return;
        }

        // 幂等防重：该日期已有归档记录（上次已入库但删 key 前进程崩溃的极端场景），
        // 跳过重复入库，直接清理归档 key
        Long existing = hotKeywordsService.lambdaQuery()
                .eq(HotKeywords::getRecordDate, recordDate)
                .count();
        if (existing != null && existing > 0) {
            log.warn("热搜 {} 已有 {} 条归档记录，跳过重复入库", recordDate, existing);
            cacheClient.delete(archiveKey);
            return;
        }

        // 读取归档 Top10（有序 List，保持热搜顺序）
        List<HotKeywords> list = new ArrayList<>();
        for (CacheClient.ZSetEntry entry : top) {
            HotKeywords entity = new HotKeywords();
            entity.setKeyword(Objects.requireNonNull(entry.value()));
            entity.setSearchCount((int) entry.score());
            entity.setRecordDate(recordDate);
            entity.setUpdateTime(LocalDateTime.now());
            list.add(entity);
        }

        // 入库：失败抛出异常标记本次调度失败（归档 key 保留，等下次收编）
        try {
            hotKeywordsService.saveBatch(list);
        } catch (Exception e) {
            log.error("热搜归档入库失败，归档 key 保留待下次调度收编：{}", recordDate, e);
            throw e;
        }

        // 入库成功后才删除归档 key
        cacheClient.delete(archiveKey);

        XxlJobHelper.handleSuccess("归档完成，共 " + list.size() + " 条热搜关键词");
    }
}
