package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.task.pojo.entity.RankTaskMonthly;
import com.rambo.module.task.pojo.vo.RankTaskMonthlyVO;

import java.util.List;

public interface RankTaskMonthlyService extends IService<RankTaskMonthly> {
    /**
     * 获取月排名任务
     * @param month 月份，格式为 YYYY-MM
     * @return 月排名任务列表
     */
    List<RankTaskMonthlyVO> getMonthlyRank(String month);

    /**
     * 获取总排名
     * @return 总排名列表
     */
    List<RankTaskMonthlyVO> getTotalRank();
}
