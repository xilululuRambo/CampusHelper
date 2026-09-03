package com.rambo.module.task.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.dto.TaskOrderQueryDTO;
import com.rambo.module.task.pojo.vo.TaskOrderVO;
import com.rambo.module.task.server.service.TaskOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/task/order")
@Tag(name = "任务订单接口")
public class TaskOrderController {

    @Resource
    private TaskOrderService taskOrderService;

    /**
     * 分页查询我的订单（发布者/接单者都能看自己的）
     *
     * @param queryDTO 查询参数
     * @return 分页结果集
     */
    @GetMapping("/my")
    @Operation(summary = "分页查询我的订单（发布者/接单者）")
    public Result<PageResult<TaskOrderVO>> myOrderPage(TaskOrderQueryDTO queryDTO) {
        return Result.success(taskOrderService.getMyOrderPage(queryDTO));
    }
}