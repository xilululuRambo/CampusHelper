package com.rambo.module.admin.server.service.impl;

import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rambo.common.constants.MessageConstants;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AdminGoodsCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminGoodsDetailVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsEvaluationVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsListItemVO;
import com.rambo.module.admin.server.service.AdminGoodsService;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.entity.GoodsCategory;
import com.rambo.module.goods.pojo.entity.GoodsEvaluation;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.server.service.GoodsCategoryService;
import com.rambo.module.goods.server.service.GoodsEvaluationService;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.rambo.module.goods.server.service.GoodsWorkflowService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class AdminGoodsServiceImpl implements AdminGoodsService {
    @Resource
    private GoodsService goodsService;
    @Resource
    private GoodsCategoryService categoryService;
    @Resource
    private GoodsEvaluationService goodsEvaluationService;
    @Resource
    private GoodsOrderService goodsOrderService;
    @Resource
    private GoodsWorkflowService goodsWorkflowService;

    /**
     * 分页查询商品列表
     */
    @Override
    public PageResult<AdminGoodsListItemVO> getGoodsList(AdminGoodsQueryDTO queryDTO) {
        Page<Goods> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<Goods> goodsPage = goodsService.lambdaQuery()
                .like(queryDTO.getKeyword() != null, Goods::getTitle, queryDTO.getKeyword())
                .eq(queryDTO.getStatus() != null, Goods::getStatus, queryDTO.getStatus())
                .eq(queryDTO.getCategoryId() != null, Goods::getCategoryId, queryDTO.getCategoryId())
                .eq(queryDTO.getOwnerId() != null, Goods::getOwnerId, queryDTO.getOwnerId())
                .ge(queryDTO.getStartPrice() != null, Goods::getPrice, queryDTO.getStartPrice())
                .le(queryDTO.getEndPrice() != null, Goods::getPrice, queryDTO.getEndPrice())
                .ge(queryDTO.getStartCreateTime() != null, Goods::getCreateTime, queryDTO.getStartCreateTime())
                .le(queryDTO.getEndCreateTime() != null, Goods::getCreateTime, queryDTO.getEndCreateTime())
                .orderByDesc(Goods::getCreateTime)
                .page(page);
        if (goodsPage.getRecords().isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }
        List<AdminGoodsListItemVO> itemList = goodsPage.getRecords().stream()
                .map(goods -> {
                    AdminGoodsListItemVO item = new AdminGoodsListItemVO();
                    BeanUtil.copyProperties(goods, item);
                    return item;
                }).toList();
        return new PageResult<>(goodsPage.getTotal(), itemList);
    }

    /**
     * 查询商品详情
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_VIEW, descriptionEL = "'管理员查看商品详情 id=' + #id")
    public AdminGoodsDetailVO getGoodsDetail(Long id) {
        log.info("管理员查看商品详情 id={}", id);
        Goods goods = goodsService.getById(id);
        if (goods == null) {
            throw new BusinessException(MessageConstants.GOODS_NOT_FOUND);
        }
        AdminGoodsDetailVO detail = new AdminGoodsDetailVO();
        BeanUtil.copyProperties(goods, detail);
        return detail;
    }

    /**
     * 强制下架/恢复商品（编排层：下架规则在 goods 模块，事务统一控制）
     * 强制下架联动：取消该商品所有未完成订单（已付款订单自动退款），任一失败整体回滚
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_STATUS_UPDATE_BY_ADMIN, descriptionEL = "'管理员更新商品状态 id=' + #id")
    public void updateGoodsStatus(Long id, GoodsStatus goodsStatus) {
        goodsService.updateGoodsStatusByAdmin(id, goodsStatus);

        // 强制下架：取消该商品所有未完成订单（待付款/待发货/待收货）
        if (goodsStatus == GoodsStatus.DISABLED) {
            List<GoodsOrder> activeOrders = goodsOrderService.lambdaQuery()
                    .eq(GoodsOrder::getGoodsId, id)
                    .in(GoodsOrder::getOrderStatus,
                            GoodsOrderStatus.PENDING_PAYMENT,
                            GoodsOrderStatus.PENDING_SHIP,
                            GoodsOrderStatus.PENDING_CONFIRM)
                    .list();
            for (GoodsOrder order : activeOrders) {
                goodsWorkflowService.cancelOrderByAdmin(order.getId());
            }
        }
    }

    /**
     * 添加商品分类
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "null",
            action = OperationActionEnum.GOODS_CATEGORY_ADD, descriptionEL = "'管理员新增商品分类'")
    public void addCategory(AdminGoodsCategoryDTO categoryDTO) {
        GoodsCategory category = new GoodsCategory();
        BeanUtil.copyProperties(categoryDTO, category);
        try {
            categoryService.saveOrUpdate(category);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MessageConstants.GOODS_CATEGORY_NAME_EXIST);
        }
    }

    /**
     * 删除商品分类（校验分类下无商品）
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_CATEGORY_DELETE, descriptionEL = "'管理员删除商品分类 id=' + #id")
    public void deleteCategory(Long id) {
        Long goodsCount = goodsService.lambdaQuery()
                .eq(Goods::getCategoryId, id)
                .count();
        if (goodsCount > 0) {
            throw new BusinessException(MessageConstants.GOODS_CATEGORY_HAS_GOODS);
        }
        categoryService.removeById(id);
    }

    /**
     * 修改商品分类
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_CATEGORY_UPDATE, descriptionEL = "'管理员修改商品分类 id=' + #id")
    public void updateCategory(Long id, AdminGoodsCategoryDTO categoryDTO) {
        GoodsCategory category = categoryService.getById(id);
        if (category == null) {
            throw new BusinessException(MessageConstants.GOODS_CATEGORY_NOT_FOUND);
        }
        BeanUtil.copyProperties(categoryDTO, category);
        categoryService.updateById(category);
    }

    /**
     * 分页查询全站商品评价
     */
    @Override
    public PageResult<AdminGoodsEvaluationVO> getEvaluationList(AdminGoodsEvaluationQueryDTO queryDTO) {
        Page<GoodsEvaluation> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<GoodsEvaluation> evaluationPage = goodsEvaluationService.lambdaQuery()
                .eq(queryDTO.getFromUid() != null, GoodsEvaluation::getFromUid, queryDTO.getFromUid())
                .eq(queryDTO.getToUid() != null, GoodsEvaluation::getToUid, queryDTO.getToUid())
                .ge(queryDTO.getMinScore() != null, GoodsEvaluation::getScore, queryDTO.getMinScore())
                .le(queryDTO.getMaxScore() != null, GoodsEvaluation::getScore, queryDTO.getMaxScore())
                .ge(queryDTO.getStartCreateTime() != null, GoodsEvaluation::getCreateTime, queryDTO.getStartCreateTime())
                .le(queryDTO.getEndCreateTime() != null, GoodsEvaluation::getCreateTime, queryDTO.getEndCreateTime())
                .orderByDesc(GoodsEvaluation::getCreateTime)
                .page(page);
        if (evaluationPage.getRecords().isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }
        List<AdminGoodsEvaluationVO> itemList = evaluationPage.getRecords().stream()
                .map(evaluation -> {
                    AdminGoodsEvaluationVO item = new AdminGoodsEvaluationVO();
                    BeanUtil.copyProperties(evaluation, item);
                    return item;
                }).toList();
        return new PageResult<>(evaluationPage.getTotal(), itemList);
    }

    /**
     * 删除评价（处理辱骂/骚扰等违规评价）
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_EVALUATION_DELETE, descriptionEL = "'管理员删除商品评价 id=' + #id")
    public void deleteEvaluation(Long id) {
        if (!goodsEvaluationService.removeById(id)) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_NOT_FOUND);
        }
    }
}
