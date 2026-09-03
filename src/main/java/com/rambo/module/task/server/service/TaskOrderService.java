package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskOrderQueryDTO;
import com.rambo.module.task.pojo.entity.TaskOrder;
import com.rambo.module.task.pojo.vo.TaskOrderVO;
import com.rambo.module.task.server.mapper.TaskOrderMapper;

public interface TaskOrderService extends IService<TaskOrder>{

    /**
     * 自动创建订单（内部事务调用，不对外暴露）
     */
    void autoCreateOrder(Long taskId, Long publisherId, Long applicantId);

    /**
     * 分页查询我的订单（发布者/接单者都能看自己的）
     */
    PageResult<TaskOrderVO> getMyOrderPage(TaskOrderQueryDTO queryDTO);
}