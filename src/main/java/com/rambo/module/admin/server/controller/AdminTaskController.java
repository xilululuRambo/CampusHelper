package com.rambo.module.admin.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.admin.pojo.dto.AdminEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminTaskDetailVO;
import com.rambo.module.admin.pojo.vo.AdminTaskListItemVO;
import com.rambo.module.admin.server.service.AdminTaskService;
import com.rambo.module.task.pojo.vo.EvaluationVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/task")
@Validated
@Slf4j
@Tag(name = "管理员任务接口")
public class AdminTaskController {
    @Resource
    private AdminTaskService adminTaskService;

    /**
     * 管理员获取任务列表
     * @param adminTaskQueryDTO 查询参数
     * @return 任务列表分页结果VO
     */
    @GetMapping("/list")
    public Result<PageResult<AdminTaskListItemVO>> getTaskList(AdminTaskQueryDTO adminTaskQueryDTO) {
        log.info("获取任务列表:{}",adminTaskQueryDTO);
        PageResult<AdminTaskListItemVO> pageResult = adminTaskService.getTaskList(adminTaskQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/detail/{id}")
    public Result<AdminTaskDetailVO> getTaskDetail(@PathVariable Long id) {
        log.info("获取任务详情:{}",id);
        AdminTaskDetailVO detail = adminTaskService.getTaskDetail(id);
        return Result.success(detail);
    }

    /**
     * 下架任务
     * @param id 任务ID
     * @return 无
     */
    @PostMapping("/down/{id}")
    public Result<Void> downTask(@PathVariable Long id) {
        log.info("下架任务:{}",id);
        adminTaskService.downTask(id);
        return Result.success();
    }

    /**
     * 添加任务分类
     * @param categoryDTO 任务分类DTO参数
     * @return 无
     */
    @PostMapping("/category/add")
    public Result<Void> addCategory(@Valid @RequestBody AdminTaskCategoryDTO categoryDTO) {
        log.info("添加任务分类:{}",categoryDTO);
        adminTaskService.addCategory(categoryDTO);
        return Result.success();
    }

    /**
     * 删除任务分类
     * @param id 任务分类ID
     * @return 无
     */
    @PostMapping("/category/delete/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        log.info("删除任务分类:{}",id);
        adminTaskService.deleteCategory(id);
        return Result.success();
    }

    /**
     * 修改任务分类
     * @param categoryDTO 任务分类DTO参数
     */
    @PostMapping("/category/update/{id}")
    public Result<Void> updateCategory(@PathVariable Long id, @Valid @RequestBody AdminTaskCategoryDTO categoryDTO) {
        log.info("修改任务分类: {},{}",id,categoryDTO);
        adminTaskService.updateCategory(id,categoryDTO);
        return Result.success();
    }

    /**
     * 分页查询全站任务评价
     * @param queryDTO 查询参数（评价人/被评价人/评分区间/时间范围）
     * @return 评价分页结果
     */
    @GetMapping("/evaluation/list")
    public Result<PageResult<EvaluationVO>> evaluationList(AdminEvaluationQueryDTO queryDTO) {
        log.info("管理员分页查询任务评价: {}", queryDTO);
        return Result.success(adminTaskService.getEvaluationList(queryDTO));
    }

    /**
     * 删除评价（处理辱骂/骚扰等违规评价）
     * @param id 评价ID
     * @return 删除结果
     */
    @DeleteMapping("/evaluation/{id}")
    public Result<Void> deleteEvaluation(@PathVariable Long id) {
        log.info("管理员删除评价: {}", id);
        adminTaskService.deleteEvaluation(id);
        return Result.success();
    }
}
