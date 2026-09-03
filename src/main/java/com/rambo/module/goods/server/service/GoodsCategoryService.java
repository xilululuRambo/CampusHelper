package com.rambo.module.goods.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.goods.pojo.entity.GoodsCategory;
import com.rambo.module.goods.pojo.vo.GoodsCategoryVO;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface GoodsCategoryService extends IService<GoodsCategory> {
    /**
     * 获取商品分类名称列表
     * @return 商品分类名称列表
     */
    List<GoodsCategoryVO> getCategoryNameList();

    /**
     * 校验商品分类是否存在
     *
     * @param categoryId 商品分类ID
     * @return 商品分类是否存在
     */
    boolean checkCategoryExist(@NotNull(message = "商品分类不能为空") Long categoryId);
}
