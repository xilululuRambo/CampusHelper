package com.rambo.module.goods.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.goods.pojo.dto.GoodsEvaluationDTO;
import com.rambo.module.goods.pojo.entity.GoodsEvaluation;
import com.rambo.module.goods.pojo.vo.GoodsEvaluationVO;

public interface GoodsEvaluationService extends IService<GoodsEvaluation> {
    /**
     * 新增评价
     *
     * @param evaluationDTO 评价DTO
     */
    void addEvaluation(GoodsEvaluationDTO evaluationDTO);

    /**
     * 根据订单ID查看双方评价
     *
     * @param orderId 订单ID
     * @return 包含买家评价和卖家评价的VO
     */
    GoodsEvaluationVO getEvaluationsByOrderId(Long orderId);
}
