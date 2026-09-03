package com.rambo.module.task.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.dto.TaskApplicationQueryDTO;
import com.rambo.module.task.pojo.vo.TaskApplicationVO;
import com.rambo.module.task.server.service.TaskApplicationService;
import com.rambo.module.task.server.service.TaskWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/task/application")
@Slf4j
@Tag(name = "任务申请接口")
@Validated
public class TaskApplicationController {

    @Resource
    private TaskApplicationService taskApplicationService;
    @Resource
    private TaskWorkflowService taskWorkflowService;

    /**
     * 申请任务
     *
     * @param taskId 任务ID
     */
    @PostMapping("/{taskId}")
    @Operation(summary = "申请任务")
    public Result<Void> applyTask(@PathVariable Long taskId, @NotBlank(message = "申请原因不能为空") String reason) {
        log.info("申请任务，任务ID：{}，申请原因：{}", taskId, reason);
        taskWorkflowService.applyForTask(taskId, reason);
        return Result.success();
    }

    /**
     * 申请者取消任务申请
     *
     * @param id 申请ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "申请者取消任务申请")
    public Result<Void> cancelTaskApplication(@PathVariable Long id) {
        log.info("申请者取消任务申请，申请ID：{}", id);
        taskApplicationService.cancelTaskApplication(id);
        return Result.success();
    }

    /**
     * 申请者确认完成任务
     *
     * @param id               申请ID
     * @param completeEvidence 完成证据文件
     */
    @PutMapping("/{id}/confirm-complete")
    @Operation(summary = "申请者确认完成任务")
    public Result<Void> confirmCompleteTask(@PathVariable Long id,
                                            @RequestPart(name = "completeEvidence", required = false) MultipartFile completeEvidence) {
        log.info("申请者确认完成任务，申请ID：{}", id);
        taskWorkflowService.completeTaskApplication(id, completeEvidence);
        return Result.success();
    }

    /**
     * 分页查询指定任务的申请列表
     *
     * @param taskId 任务ID
     * @param taskApplicationQueryDTO 查询参数
     * @return 任务申请VO列表分页结果
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "分页查询指定任务的申请列表")
    public Result<PageResult<TaskApplicationVO>> taskApplicationList(@PathVariable Long taskId,TaskApplicationQueryDTO taskApplicationQueryDTO) {
        log.info("分页查询指定任务的申请列表，查询参数：{},{}", taskId, taskApplicationQueryDTO);
        PageResult<TaskApplicationVO> pageResult = taskApplicationService.taskApplicationList(taskId, taskApplicationQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 任务发布者同意申请
     *
     * @param id 申请ID
     */
    @PutMapping("/{id}/accept")
    @Operation(summary = "任务发布者同意申请")
    public Result<Void> acceptApplication(@PathVariable Long id) {
        log.info("任务发布者同意申请，申请ID：{}", id);
        taskWorkflowService.acceptApplication(id);
        return Result.success();
    }

    /**
     * 任务发布者拒绝申请
     *
     * @param id 申请ID
     */
    @PutMapping("/{id}/reject")
    @Operation(summary = "任务发布者拒绝申请")
    public Result<Void> rejectApplication(@PathVariable Long id) {
        log.info("任务发布者拒绝申请，申请ID：{}", id);
        taskWorkflowService.rejectApplication(id);
        return Result.success();
    }

}