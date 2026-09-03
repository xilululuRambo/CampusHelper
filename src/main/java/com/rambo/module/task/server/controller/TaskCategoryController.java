package com.rambo.module.task.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.vo.CategoryVO;
import com.rambo.module.task.server.service.TaskCategoryService;
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
@Slf4j
@RequestMapping("/task/category")
@Validated
@Tag(name = "任务分类接口")
public class TaskCategoryController {
    @Resource
    private TaskCategoryService taskCategoryService;

    /**
     * 获取所有分类名称
     * @return Result<List<CategoryVO>>
     */
    @GetMapping
    @NoAuthAnnotation
    @Operation(summary = "获取所有分类名称")
    public Result<List<CategoryVO>> getAllCategories() {
        log.info("获取所有分类名称");
        List<CategoryVO> categoryList = taskCategoryService.getAllCategories();
        return Result.success(categoryList);
       }
}
