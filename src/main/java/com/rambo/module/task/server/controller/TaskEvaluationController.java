package com.rambo.module.task.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.dto.EvaluationDTO;
import com.rambo.module.task.pojo.dto.EvaluationQueryDTO;
import com.rambo.module.task.pojo.vo.EvaluationVO;
import com.rambo.module.task.server.service.TaskEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/task/evaluation")
@Tag(name = "任务评价接口")
public class TaskEvaluationController {

    @Resource
    private TaskEvaluationService taskEvaluationService;

    /**
     * 提交评价（任务完成后可用）
     * @param evaluationDTO 评价DTO
     * @return 成功
     */
    @PostMapping
    @Operation(summary = "提交评价（任务完成后可用）")
    public Result<Void> add(@Valid @RequestBody EvaluationDTO evaluationDTO) {
        taskEvaluationService.addEvaluation(evaluationDTO);
        return Result.success();
    }

    /**
     * 根据订单ID查询评价列表
     * @param orderId 订单ID
     * @param queryDTO 查询参数
     * @return 评价列表
     */
    @GetMapping("/order/{orderId}")
    @Operation(summary = "根据订单ID查询评价列表")
    public Result<PageResult<EvaluationVO>> list(
            @PathVariable Long orderId,
            EvaluationQueryDTO queryDTO) {
        return Result.success(taskEvaluationService.getByOrderId(orderId, queryDTO));
    }
}