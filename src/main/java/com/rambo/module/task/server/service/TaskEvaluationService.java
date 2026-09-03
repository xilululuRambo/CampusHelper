package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.EvaluationDTO;
import com.rambo.module.task.pojo.dto.EvaluationQueryDTO;
import com.rambo.module.task.pojo.entity.TaskEvaluation;
import com.rambo.module.task.pojo.vo.EvaluationVO;

public interface TaskEvaluationService extends IService<TaskEvaluation> {

    /**
     * 发布评价
     * @param evaluationDTO 评价DTO
     */
    void addEvaluation(EvaluationDTO evaluationDTO);

    /**
     * 根据订单ID查询评价列表
     * @param orderId 订单ID
     * @param queryDTO 查询参数
     * @return 评价列表
     */
    PageResult<EvaluationVO> getByOrderId(Long orderId, EvaluationQueryDTO queryDTO);
}