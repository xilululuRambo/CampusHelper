package com.rambo.module.task.server.controller;

import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.vo.RankTaskMonthlyVO;
import com.rambo.module.task.server.service.RankTaskMonthlyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/task/rank/monthly")
@Slf4j
@Validated
@Tag(name = "月排名任务接口")
public class RankTaskMonthlyController {
    @Resource
    private RankTaskMonthlyService rankTaskMonthlyService;

    /**
     * 获取月排名任务
     * @param month 月份，格式为 YYYY-MM
     * @return 月排名任务列表
     */
    @GetMapping("/monthly")
    public Result<List<RankTaskMonthlyVO>> getMonthlyRank(String month) {
        log.info("获取月排名任务，月份：{}", month);
        // 从 Redis ZSet 倒序查询排名任务
        List<RankTaskMonthlyVO> monthlyRankList = rankTaskMonthlyService.getMonthlyRank(month);
        return Result.success(monthlyRankList);
    }

    /**
     * 获取总排名
     * @return 总排名
     */
    @GetMapping("/total")
    public Result<List<RankTaskMonthlyVO>> getTotalRank() {
        log.info("获取总排名");
        // 从 Redis ZSet 倒序查询总排名
        List<RankTaskMonthlyVO> totalRank = rankTaskMonthlyService.getTotalRank();
        return Result.success(totalRank);
    }
}
