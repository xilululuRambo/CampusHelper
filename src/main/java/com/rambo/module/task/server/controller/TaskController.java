package com.rambo.module.task.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.annotation.PreventDuplicate;
import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.dto.TaskDTO;
import com.rambo.module.task.pojo.dto.TaskMyQueryDTO;
import com.rambo.module.task.pojo.dto.TaskQueryDTO;
import com.rambo.module.task.pojo.vo.TaskVO;
import com.rambo.module.task.server.service.TaskApplicationService;
import com.rambo.module.task.server.service.TaskService;
import com.rambo.module.task.server.service.TaskWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/task")
@Validated
@Tag(name = "任务接口")
public class TaskController {

    @Resource
    private TaskWorkflowService taskWorkflowService;
    @Resource
    private TaskService taskService;
    @Resource
    private TaskApplicationService taskApplicationService;

    /**
     * 发布任务
     *
     * @param taskDTO 任务DTO，包含任务信息
     */
    @PostMapping
    @PreventDuplicate(scene = "publishTask")
    @Operation(summary = "发布任务")
    public Result<Void> publishTask(@Valid @RequestBody TaskDTO taskDTO) {
        log.info("发布任务：{}", taskDTO);
        taskService.publishTask(taskDTO);
        return Result.success();
    }

    /**
     * 更新任务
     *
     * @param taskId  任务ID
     * @param taskDTO 任务DTO，包含任务信息
     */
    @PutMapping("/{taskId}")
    @Operation(summary = "更新任务")
    public Result<Void> updateTask(@PathVariable Long taskId, @Valid @RequestBody TaskDTO taskDTO) {
        log.info("更新任务：{},{}", taskId, taskDTO);
        taskService.updateTask(taskId, taskDTO);
        return Result.success();
    }

    /**
     * 取消任务
     *
     * @param taskId 任务ID
     */
    @DeleteMapping("/{taskId}")
    @Operation(summary = "取消任务")
    public Result<Void> cancelTask(@PathVariable Long taskId) {
        log.info("取消任务：{}", taskId);
        taskWorkflowService.cancelTask(taskId);
        return Result.success();
    }

    /**
     * 发布者确认完成任务
     *
     * @param taskId 任务ID
     */
    @PutMapping("/{taskId}/confirm-complete")
    @Operation(summary = "发布者确认完成任务")
    public Result<Void> confirmCompleteTask(@PathVariable Long taskId) {
        log.info("发布者确认完成任务：{}", taskId);
        taskWorkflowService.confirmCompleteTask(taskId);
        return Result.success();
    }

    /**
     * 查询我发布的任务列表
     *
     * @param taskMyQueryDTO 查询参数
     * @return 任务列表分页结果，包含任务VO列表
     */
    @GetMapping("/my-published")
    @Operation(summary = "我发布的任务列表")
    public Result<PageResult<TaskVO>> myPublishedTasks(TaskMyQueryDTO taskMyQueryDTO) {
        log.info("查询我发布的任务，参数：{}", taskMyQueryDTO);
        PageResult<TaskVO> page = taskService.getMyPublishedTasks(taskMyQueryDTO);
        return Result.success(page);
    }

    /**
     * 查询我承接的任务列表
     *
     * @param taskMyQueryDTO 查询参数
     * @return 任务列表分页结果，包含任务VO列表
     */
    @GetMapping("/my-accepted")
    @Operation(summary = "我承接的任务列表")
    public Result<PageResult<TaskVO>> myAcceptedTasks(TaskMyQueryDTO taskMyQueryDTO) {
        log.info("查询我承接的任务，参数：{}", taskMyQueryDTO);
        PageResult<TaskVO> page = taskService.getMyAcceptedTasks(taskMyQueryDTO);
        return Result.success(page);
    }

    /**
     * 查询所有任务
     *
     * @param taskQueryDTO 搜索参数
     * @return 任务列表分页结果，包含任务VO列表
     */
    @GetMapping("/list")
    @NoAuthAnnotation
    @Operation(summary = "获取所有任务")
    public Result<PageResult<TaskVO>> getAllTasks(TaskQueryDTO taskQueryDTO) {
        log.info("获取所有任务：{}", taskQueryDTO);
        PageResult<TaskVO> page = taskService.getAllTasks(taskQueryDTO);
        return Result.success(page);
    }

    /**
     * 查询任务详情
     * <p>需登录访问（移除 @NoAuthAnnotation，防止匿名批量抓取发布者地址）；
     * 完整地址仅任务参与方可见，其他登录用户返回脱敏地址（见 TaskServiceImpl）。</p>
     *
     * @param taskId 任务ID
     * @return 任务详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务详情")
    public Result<TaskVO> getTaskDetail(@PathVariable Long taskId) {
        log.info("获取任务详情：{}", taskId);
        TaskVO taskVO = taskService.getTaskDetail(taskId);
        return Result.success(taskVO);
    }
}