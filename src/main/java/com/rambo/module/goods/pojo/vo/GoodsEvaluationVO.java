package com.rambo.module.goods.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.rambo.module.goods.pojo.entity.GoodsEvaluation;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "订单评价信息（包含双方评价）")
public class GoodsEvaluationVO {
    @Schema(description = "买家对卖家的评价")
    private EvaluationDetail buyerToSeller;

    @Schema(description = "卖家对买家的评价")
    private EvaluationDetail sellerToBuyer;

    public GoodsEvaluationVO(GoodsEvaluation buyerToSellerEntity, GoodsEvaluation sellerToBuyerEntity) {
        this.buyerToSeller = buyerToSellerEntity != null ? new EvaluationDetail(buyerToSellerEntity) : null;
        this.sellerToBuyer = sellerToBuyerEntity != null ? new EvaluationDetail(sellerToBuyerEntity) : null;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(description = "单方评价详情")
    public static class EvaluationDetail {
        @Schema(description = "评价用户ID")
        private Long fromUid;
        @Schema(description = "被评价用户ID")
        private Long toUid;
        @Schema(description = "评分（1-5）")
        private Integer score;
        @Schema(description = "评价内容")
        private String content;

        public EvaluationDetail(GoodsEvaluation entity) {
            this.fromUid = entity.getFromUid();
            this.toUid = entity.getToUid();
            this.score = entity.getScore();
            this.content = entity.getContent();
        }
    }
}