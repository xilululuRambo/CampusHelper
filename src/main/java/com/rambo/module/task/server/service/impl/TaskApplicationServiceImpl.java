package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.MessageConstants;
import com.rambo.module.task.enums.TaskApplyStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.context.IdHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskApplicationQueryDTO;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.pojo.entity.TaskApplication;
import com.rambo.module.task.pojo.vo.TaskApplicationVO;
import com.rambo.module.task.server.mapper.TaskApplicationMapper;
import com.rambo.module.task.server.service.TaskApplicationService;
import com.rambo.module.task.server.service.TaskService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class TaskApplicationServiceImpl extends ServiceImpl<TaskApplicationMapper, TaskApplication> implements TaskApplicationService {

    @Resource
    private TaskService taskService;

    /**
     * 分页查询指定任务的申请列表
     *
     * @param taskId                  任务ID
     * @param taskApplicationQueryDTO 查询参数DTO
     * @return 任务申请VO列表分页结果
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.APPLICATION,
            targetIdEL = "#taskId",
            action = OperationActionEnum.APPLICATION_VIEW, descriptionEL = "'查看任务申请列表'")
    public PageResult<TaskApplicationVO> taskApplicationList(Long taskId, TaskApplicationQueryDTO taskApplicationQueryDTO) {
        Long userId = IdHolder.getId();


        var query = lambdaQuery();

        //校验状态是否存在
        if (taskApplicationQueryDTO.getApplyStatus() != null) {
            query.eq(TaskApplication::getStatus, taskApplicationQueryDTO.getApplyStatus());
        }

        //校验任务是否存在
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
        }

        //查询当前任务的所有申请
        query.eq(TaskApplication::getTaskId, taskId);

        // 权限判断：是否是该任务发布者
        boolean isPublisher = task.getPublisherId().equals(userId);

        //不是发布者，只能查询自己的申请
        if (!isPublisher) {
            query.eq(TaskApplication::getApplicantId, userId);
        }

        //分页构造
        Page<TaskApplication> page = new Page<>(taskApplicationQueryDTO.getPageNum(), taskApplicationQueryDTO.getPageSize());

        Page<TaskApplication> taskApplicationList = query.page(page);
        //校验是否有数据
        if (taskApplicationList.getTotal() == 0) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        List<TaskApplication> records = taskApplicationList.getRecords();

        //转换为VO列表
        List<TaskApplicationVO> applicationList = records.stream()
                .map(t -> {
                    TaskApplicationVO item = BeanUtil.copyProperties(t, TaskApplicationVO.class);
                    item.setStatus(t.getStatus() != null ? t.getStatus().getCode() : null);
                    return item;
                })
                .toList();
        PageResult<TaskApplicationVO> result = new PageResult<>(taskApplicationList.getTotal(), applicationList);
        log.info("用户 {} 查看任务 {} 的申请列表", userId, taskId);
        return result;
    }

    /**
     * 申请者取消任务申请（仅修改申请状态）
     *
     * @param id 申请ID
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.APPLICATION,
            targetIdEL = "#id",
            action = OperationActionEnum.APPLICATION_CANCEL, descriptionEL = "'取消任务申请'")
    public void cancelTaskApplication(Long id) {
        //校验申请是否存在且申请人是否是当前用户
        TaskApplication app = getById(id);
        if (app == null || !app.getApplicantId().equals(IdHolder.getId())) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        //校验申请状态是否为待处理
        if (app.getStatus() != TaskApplyStatus.PENDING_APPLICATION) {
            throw new BusinessException(MessageConstants.TASK_APPLICATION_CANCEL_ERROR);
        }

        //更新申请状态为已取消
        app.setStatus(TaskApplyStatus.CANCELLED_APPLICATION);

        // 乐观锁更新是否成功
        boolean updated = updateById(app);
        if (!updated) {
            // 申请已被其他线程修改，需要抛出业务异常让用户感知
            throw new BusinessException(MessageConstants.APPLICATION_STATUS_CHANGED);
        }
        log.info("用户 {} 取消任务申请 {}", IdHolder.getId(), id);
    }

    /**
     * 任务取消时清理所有非终态申请（待处理→已拒绝，已接受→已取消）
     *
     * @param taskId 任务ID
     */
    @Override
    @Transactional
    public void cancelTaskApplications(Long taskId) {
        // 待处理申请 → 已拒绝
        lambdaUpdate()
                .eq(TaskApplication::getTaskId, taskId)
                .eq(TaskApplication::getStatus, TaskApplyStatus.PENDING_APPLICATION)
                .set(TaskApplication::getStatus, TaskApplyStatus.REJECTED_APPLICATION)
                .update();
        // 已接受申请 → 已取消（任务取消后接单关系失效）
        lambdaUpdate()
                .eq(TaskApplication::getTaskId, taskId)
                .eq(TaskApplication::getStatus, TaskApplyStatus.ACCEPTED_APPLICATION)
                .set(TaskApplication::getStatus, TaskApplyStatus.CANCELLED_APPLICATION)
                .update();
    }

    /**
     * 获取指定任务中状态为“已完成”的申请（即申请人已提交完成证据的记录）
     *
     * @param taskId 任务ID
     * @return 已完成申请记录
     */
    @Override
    public TaskApplication getCompletedApplication(Long taskId) {
        return lambdaQuery()
                .eq(TaskApplication::getTaskId, taskId)
                .eq(TaskApplication::getStatus, TaskApplyStatus.COMPLETED_APPLICATION)
                .one();
    }

    /**
     * 检查用户是否已经申请过该任务（防重）
     *
     * @param taskId 任务ID
     * @param applicantId 申请人ID
     * @return 是否已申请
     */
    @Override
    public boolean existsByTaskAndApplicant(Long taskId, Long applicantId) {
        return lambdaQuery()
                .eq(TaskApplication::getTaskId, taskId)
                .eq(TaskApplication::getApplicantId, applicantId)
                .exists();
    }

    /**
     * 拒绝任务所有其他待处理申请（除指定申请外）
     * @param taskId 任务ID
     * @param excludeApplicationId 排除的申请ID
     */
    @Override
    public void rejectOtherApplications(Long taskId, Long excludeApplicationId) {
        lambdaUpdate()
                .eq(TaskApplication::getTaskId, taskId)
                .ne(TaskApplication::getId, excludeApplicationId)
                .set(TaskApplication::getStatus, TaskApplyStatus.REJECTED_APPLICATION)
                .update();
    }
}