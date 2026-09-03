package com.rambo.module.admin.server.controller;

import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.admin.pojo.dto.AdminGoodsCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminGoodsQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminGoodsDetailVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsEvaluationVO;
import com.rambo.module.admin.pojo.vo.AdminGoodsListItemVO;
import com.rambo.module.admin.server.service.AdminGoodsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/goods")
@Validated
@Slf4j
@Tag(name = "管理员商品接口")
public class AdminGoodsController {
    @Resource
    private AdminGoodsService adminGoodsService;

    /**
     * 分页查询商品列表
     * @param queryDTO 查询参数（关键词/状态/分类/卖家/价格区间/时间范围）
     * @return 商品列表分页结果VO
     */
    @GetMapping("/list")
    public Result<PageResult<AdminGoodsListItemVO>> getGoodsList(AdminGoodsQueryDTO queryDTO) {
        log.info("管理员分页查询商品列表: {}", queryDTO);
        return Result.success(adminGoodsService.getGoodsList(queryDTO));
    }

    /**
     * 查询商品详情
     * @param id 商品ID
     * @return 商品详情VO
     */
    @GetMapping("/detail/{id}")
    public Result<AdminGoodsDetailVO> getGoodsDetail(@PathVariable Long id) {
        log.info("管理员查询商品详情: {}", id);
        return Result.success(adminGoodsService.getGoodsDetail(id));
    }

    /**
     * 强制下架/恢复商品
     * @param id 商品ID
     * @param goodsStatus 目标状态（NORMAL-恢复在售 / DISABLED-强制下架）
     * @return 无
     */
    @PostMapping("/status/{id}")
    public Result<Void> updateGoodsStatus(@PathVariable Long id,
                                          @NotNull(message = "商品状态不能为空") @RequestParam GoodsStatus goodsStatus) {
        log.info("管理员更新商品状态: {}, {}", id, goodsStatus);
        adminGoodsService.updateGoodsStatus(id, goodsStatus);
        return Result.success();
    }

    /**
     * 添加商品分类
     * @param categoryDTO 商品分类DTO参数
     * @return 无
     */
    @PostMapping("/category/add")
    public Result<Void> addCategory(@Valid @RequestBody AdminGoodsCategoryDTO categoryDTO) {
        log.info("管理员添加商品分类: {}", categoryDTO);
        adminGoodsService.addCategory(categoryDTO);
        return Result.success();
    }

    /**
     * 删除商品分类
     * @param id 商品分类ID
     * @return 无
     */
    @PostMapping("/category/delete/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        log.info("管理员删除商品分类: {}", id);
        adminGoodsService.deleteCategory(id);
        return Result.success();
    }

    /**
     * 修改商品分类
     * @param id 商品分类ID
     * @param categoryDTO 商品分类DTO参数
     * @return 无
     */
    @PostMapping("/category/update/{id}")
    public Result<Void> updateCategory(@PathVariable Long id, @Valid @RequestBody AdminGoodsCategoryDTO categoryDTO) {
        log.info("管理员修改商品分类: {}, {}", id, categoryDTO);
        adminGoodsService.updateCategory(id, categoryDTO);
        return Result.success();
    }

    /**
     * 分页查询全站商品评价
     * @param queryDTO 查询参数（评价人/被评价人/评分区间/时间范围）
     * @return 评价分页结果
     */
    @GetMapping("/evaluation/list")
    public Result<PageResult<AdminGoodsEvaluationVO>> evaluationList(AdminGoodsEvaluationQueryDTO queryDTO) {
        log.info("管理员分页查询商品评价: {}", queryDTO);
        return Result.success(adminGoodsService.getEvaluationList(queryDTO));
    }

    /**
     * 删除评价（处理辱骂/骚扰等违规评价）
     * @param id 评价ID
     * @return 删除结果
     */
    @DeleteMapping("/evaluation/{id}")
    public Result<Void> deleteEvaluation(@PathVariable Long id) {
        log.info("管理员删除商品评价: {}", id);
        adminGoodsService.deleteEvaluation(id);
        return Result.success();
    }
}
