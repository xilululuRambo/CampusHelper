package com.rambo.module.goods.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.enumType.CategoryStatus;
import com.rambo.module.goods.pojo.entity.GoodsCategory;
import com.rambo.module.goods.pojo.vo.GoodsCategoryVO;
import com.rambo.module.goods.server.mapper.GoodsCategoryMapper;
import com.rambo.module.goods.server.service.GoodsCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GoodsCategoryServiceImpl extends ServiceImpl<GoodsCategoryMapper, GoodsCategory> implements GoodsCategoryService {

    /**
     * 获取所有正常状态的商品分类名称列表
     * @return 所有正常状态的商品分类名称列表VO列表
     */
    @Override
    public List<GoodsCategoryVO> getCategoryNameList() {
        // 查询所有正常状态的商品分类
        List<GoodsCategory> list = lambdaQuery()
                .eq(GoodsCategory::getStatus, CategoryStatus.NORMAL).list();

        // 转换为VO列表
        return list.stream()
                .map(category -> {
                    GoodsCategoryVO item = new GoodsCategoryVO();
                    BeanUtil.copyProperties(category, item);
                    return item;
                })
                .toList();
    }

    /**
     * 校验商品分类是否存在
     *
     * @param categoryId 商品分类ID
     * @return 商品分类是否存在
     */
    @Override
    public boolean checkCategoryExist(Long categoryId) {
        return lambdaQuery()
                .eq(GoodsCategory::getId, categoryId)
                .eq(GoodsCategory::getStatus, CategoryStatus.NORMAL)
                .exists();
    }
}
