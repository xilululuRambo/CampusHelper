package com.rambo.module.goods.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.goods.pojo.dto.GoodsDTO;
import com.rambo.module.goods.pojo.dto.GoodsQueryDTO;
import com.rambo.module.goods.pojo.vo.GoodsDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsListVO;
import com.rambo.module.goods.server.service.GoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/goods")
@Slf4j
@Validated
@Tag(name = "商品接口")
public class GoodsController {

    @Resource
    private GoodsService goodsService;

    /**
     * 商品发布
     *
     * @return 成功返回空结果
     */
    @PostMapping
    //TODO@PreventDuplicate(scene = "publishGoods")
    @Operation(summary = "商品发布")
    public Result<Void> publishGoods(@Validated GoodsDTO goodsDTO,
                                     @RequestPart(name = "images") MultipartFile[] imagesFiles) {
        log.info("商品发布");
        goodsService.publishGoods(goodsDTO, imagesFiles);
        return Result.success();
    }

    /**
     * 商品更新
     *
     * @param goodsDTO 商品更新DTO
     * @return 成功返回空结果
     */
    @PutMapping("/{id}")
    @Operation(summary = "商品更新")
    public Result<Void> updateGoods(@PathVariable Long id,
                                    @Validated GoodsDTO goodsDTO,
                                    @RequestPart(name = "images", required = false) MultipartFile[] imagesFiles) {
        log.info("商品更新");
        goodsService.updateGoods(id, goodsDTO, imagesFiles);
        return Result.success();
    }

    /**
     * 商品状态更新
     *
     * @param id          商品ID
     * @param goodsStatus 商品状态
     * @return 成功返回空结果
     */
    @PutMapping("/status/{id}")
    @Operation(summary = "商品状态更新")
    public Result<Void> updateGoodsStatus(@PathVariable Long id,
                                          @NotNull(message = "商品状态不能为空") @RequestParam GoodsStatus goodsStatus) {
        log.info("商品状态更新");
        goodsService.updateGoodsStatus(id, goodsStatus);
        return Result.success();
    }

    /**
     * 商品详情
     *
     * @param id 商品ID
     * @return 商品详情VO
     */
    @GetMapping("/{id}")
    @NoAuthAnnotation
    @Operation(summary = "商品详情")
    public Result<GoodsDetailVO> getGoods(@PathVariable Long id) {
        log.info("商品详情");
        GoodsDetailVO goods = goodsService.getGoods(id);
        return Result.success(goods);
    }

    /**
     * 商品删除
     *
     * @param id 商品ID
     * @return 成功返回空结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "商品删除")
    public Result<Void> deleteGoods(@PathVariable Long id) {
        log.info("商品删除");
        goodsService.deleteGoods(id);
        return Result.success();
    }

    /**
     * 商品列表
     *
     * @return 商品列表VO列表
     */
    @GetMapping("/list")
    @NoAuthAnnotation
    @Operation(summary = "商品列表")
    public Result<PageResult<GoodsListVO>> getGoodsList(GoodsQueryDTO goodsQueryDTO) {
        log.info("商品列表");
        PageResult<GoodsListVO> pageResult = goodsService.getGoodsList(goodsQueryDTO);
        return Result.success(pageResult);
    }

    //我的发布的商品
    @GetMapping("/my")
    @Operation(summary = "我的发布的商品")
    public Result<PageResult<GoodsListVO>> getMyGoodsList(@RequestParam(required = false) GoodsStatus goodsStatus,
                                                          PageQuery pageQueryDTO) {
        log.info("我的发布的商品");
        PageResult<GoodsListVO> pageResult = goodsService.getMyGoodsList(goodsStatus, pageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 购买商品
     *
     * @return 成功返回空结果
     */
    @PostMapping("/buy/{id}")
    @Operation(summary = "购买商品")
    public Result<Void> buyGoods(@PathVariable Long id) {
        log.info("购买商品");
        goodsService.buyGoods(id);
        return Result.success();
    }
}
