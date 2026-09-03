package com.rambo.module.goods.server.controller;

import com.rambo.common.result.Result;
import com.rambo.module.goods.pojo.vo.GoodsCategoryVO;
import com.rambo.module.goods.server.service.GoodsCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/goods/category")
@Tag(name = "商品分类接口")
@Slf4j
@Validated
public class GoodsCategoryController {

    @Resource
    private GoodsCategoryService goodsCategoryService;

    /**
     * 获取商品分类名称列表
     * @return 商品分类名称列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取商品分类名称列表")
    public Result<List<GoodsCategoryVO>> getCategoryNameList() {
        log.info("获取商品分类名称列表");
        List<GoodsCategoryVO> categoryList = goodsCategoryService.getCategoryNameList();
        return Result.success(categoryList);
    }

}
