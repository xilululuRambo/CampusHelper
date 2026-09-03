package com.rambo.module.admin.server.service;

import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AdminGoodsCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminGoodsDetailVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsEvaluationVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsListItemVO;
import jakarta.validation.Valid;

public interface AdminGoodsService {
    /**
     * 分页查询商品列表
     * @param queryDTO 查询参数
     * @return 商品列表分页结果VO
     */
    PageResult<AdminGoodsListItemVO> getGoodsList(AdminGoodsQueryDTO queryDTO);

    /**
     * 查询商品详情
     * @param id 商品ID
     * @return 商品详情VO
     */
    AdminGoodsDetailVO getGoodsDetail(Long id);

    /**
     * 强制下架/恢复商品
     * @param id 商品ID
     * @param goodsStatus 目标状态
     */
    void updateGoodsStatus(Long id, GoodsStatus goodsStatus);

    /**
     * 添加商品分类
     * @param categoryDTO 商品分类DTO参数
     */
    void addCategory(AdminGoodsCategoryDTO categoryDTO);

    /**
     * 删除商品分类
     * @param id 商品分类ID
     */
    void deleteCategory(Long id);

    /**
     * 修改商品分类
     * @param id 商品分类ID
     * @param categoryDTO 商品分类DTO参数
     */
    void updateCategory(Long id, @Valid AdminGoodsCategoryDTO categoryDTO);

    /**
     * 分页查询全站商品评价
     * @param queryDTO 查询参数
     * @return 评价分页结果
     */
    PageResult<AdminGoodsEvaluationVO> getEvaluationList(AdminGoodsEvaluationQueryDTO queryDTO);

    /**
     * 删除评价
     * @param id 评价ID
     */
    void deleteEvaluation(Long id);
}
