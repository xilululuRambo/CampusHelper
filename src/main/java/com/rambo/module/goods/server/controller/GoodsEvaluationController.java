package com.rambo.module.goods.server.controller;

import com.rambo.common.annotation.PreventDuplicate;
import com.rambo.common.result.Result;
import com.rambo.module.goods.pojo.dto.GoodsEvaluationDTO;
import com.rambo.module.goods.server.service.GoodsEvaluationService;
import com.rambo.module.goods.pojo.vo.GoodsEvaluationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/goods/evaluation")
@Tag(name = "商品评价接口")
@Slf4j
@Validated
public class GoodsEvaluationController {
    @Resource
    private GoodsEvaluationService evaluationService;

    /**
     * 新增评价
     *
     * @param evaluationDTO 评价请求DTO
     */
    @PostMapping("/add")
    //TODO@PreventDuplicate(scene = "addEvaluation")
    @Operation(summary = "新增评价")
    public Result<Void> addEvaluation(@RequestBody GoodsEvaluationDTO evaluationDTO) {
        log.info("新增评价，orderId={}", evaluationDTO.getOrderId());
        evaluationService.addEvaluation(evaluationDTO);
        return Result.success();
    }

    /**
     * 根据订单ID查看双方评价
     *
     * @param orderId 订单ID
     */
    @GetMapping("/{orderId}")
    @Operation(summary = "根据订单ID查看双方评价")
    public Result<GoodsEvaluationVO> getEvaluations(@PathVariable Long orderId) {
        log.info("查看评价，orderId={}", orderId);
        GoodsEvaluationVO evaluation = evaluationService.getEvaluationsByOrderId(orderId);
        return Result.success(evaluation);
    }
}
