package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.task.pojo.entity.RankTaskMonthly;
import com.rambo.module.task.pojo.vo.RankTaskMonthlyVO;
import com.rambo.module.task.server.mapper.RankTaskMonthlyMapper;
import com.rambo.module.task.server.service.RankTaskMonthlyService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RankTaskMonthlyServiceImpl extends ServiceImpl<RankTaskMonthlyMapper, RankTaskMonthly> implements RankTaskMonthlyService {
    @Resource
    private CacheClient cacheClient;

    /**
     * 获取月任务排名
     *
     * @param month 月份，格式为 YYYY-MM
     * @return 月任务排名列表
     */
    @Override
    public List<RankTaskMonthlyVO> getMonthlyRank(String month) {
        String monthNow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        if (monthNow.equals(month)) {

            //当前月，从缓存查询排名任务
            // 从 Redis ZSet 倒序查询排名任务（返回有序 List，保持 Top10 排名顺序）
            String prefix = PrefixConstants.TASK_RANK_MONTH + month;
            List<CacheClient.ZSetEntry> range = cacheClient.zReverseRangeWithScores(prefix, 0, NumConstants.TOP_RANK_END_INDEX);

            //校验缓存是否存在数据
            if (range == null || range.isEmpty()) {
                return List.of();
            }

            //转换为VO
            List<RankTaskMonthlyVO> list = new ArrayList<>();
            AtomicInteger rankNum = new AtomicInteger(1);
            range.forEach(entry -> {
                RankTaskMonthlyVO item = new RankTaskMonthlyVO();
                item.setUserId(Long.valueOf(Objects.requireNonNull(entry.value())));
                item.setFinishCount((int) entry.score());
                item.setRankNum(rankNum.getAndIncrement());
                list.add(item);
            });
            return list;
        }

        //非当前月，从数据库查询任务排名
        //从数据库查询任务排名
        List<RankTaskMonthly> list = lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month)
                .orderByDesc(RankTaskMonthly::getFinishCount)
                .last("limit " + NumConstants.TOP_RANK_COUNT)
                .list();

        //校验数据库是否存在数据
        if (list == null || list.isEmpty()) {
            return List.of();
        }

        //转换为VO
        return list.stream().map(rankTaskMonthly ->
                BeanUtil.copyProperties(rankTaskMonthly, RankTaskMonthlyVO.class)).toList();

    }

    /**
     * 获取总排名
     *
     * @return 总排名列表
     */
    @Override
    public List<RankTaskMonthlyVO> getTotalRank() {
        // 从 Redis ZSet 倒序查询总任务排名（返回有序 List，保持排名顺序）
        String prefix = PrefixConstants.TASK_RANK_TOTAL;
        List<CacheClient.ZSetEntry> range = cacheClient.zReverseRangeWithScores(prefix, 0, -1);

        //校验缓存是否存在数据
        if (range == null || range.isEmpty()) {
            return List.of();
        }

        //转换为VO
        List<RankTaskMonthlyVO> list = new ArrayList<>();
        AtomicInteger rankNum = new AtomicInteger(1);
        range.forEach(entry -> {
            RankTaskMonthlyVO item = new RankTaskMonthlyVO();
            item.setUserId(Long.valueOf(Objects.requireNonNull(entry.value())));
            item.setFinishCount((int) entry.score());
            item.setRankNum(rankNum.getAndIncrement());
            list.add(item);
        });
        return list;
    }
}
