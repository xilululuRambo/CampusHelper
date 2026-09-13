package com.rambo.module.admin.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AdminEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminTaskDetailVO;
import com.rambo.module.admin.pojo.vo.AdminTaskListItemVO;
import com.rambo.module.admin.server.service.AdminTaskService;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.pojo.entity.TaskCategory;
import com.rambo.module.task.pojo.entity.TaskEvaluation;
import com.rambo.module.task.pojo.vo.EvaluationVO;
import com.rambo.module.task.pojo.vo.TaskVO;
import com.rambo.module.task.server.service.TaskCategoryService;
import com.rambo.module.task.server.service.TaskEvaluationService;
import com.rambo.module.task.server.service.TaskService;
import com.rambo.module.task.server.service.TaskWorkflowService;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class AdminTaskServiceImpl implements AdminTaskService {
    @Resource
    private TaskService taskService;
    @Resource
    private TaskCategoryService categoryService;
    @Resource
    private TaskWorkflowService taskWorkflowService;
    @Resource
    private TaskEvaluationService taskEvaluationService;

    /**
     * 获取任务列表
     */
    @Override
    public PageResult<AdminTaskListItemVO> getTaskList(AdminTaskQueryDTO queryDTO) {
        Page<Task> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<Task> taskList = taskService.lambdaQuery()
                .eq(queryDTO.getPublisherId() != null, Task::getPublisherId, queryDTO.getPublisherId())
                .eq(queryDTO.getCategoryId() != null, Task::getCategoryId, queryDTO.getCategoryId())
                .eq(queryDTO.getStatus() != null, Task::getStatus, queryDTO.getStatus())
                .like(queryDTO.getTitle() != null, Task::getTitle, queryDTO.getTitle())
                .ge(queryDTO.getStartReward() != null, Task::getReward, queryDTO.getStartReward())
                .le(queryDTO.getEndReward() != null, Task::getReward, queryDTO.getEndReward())
                .ge(queryDTO.getStartDeadline() != null, Task::getDeadline, queryDTO.getStartDeadline())
                .le(queryDTO.getEndDeadline() != null, Task::getDeadline, queryDTO.getEndDeadline())
                .ge(queryDTO.getStartCreateTime() != null, Task::getCreateTime, queryDTO.getStartCreateTime())
                .le(queryDTO.getEndCreateTime() != null, Task::getCreateTime, queryDTO.getEndCreateTime())
                .page(page);
        if (taskList.getRecords().isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        // 转换为VO
        List<AdminTaskListItemVO> itemList = taskList.getRecords().stream()
                .map(task -> {
                    AdminTaskListItemVO item = new AdminTaskListItemVO();
                    BeanUtil.copyProperties(task, item);
                    return item;
                }).toList();

        return new PageResult<>(taskList.getTotal(), itemList);
    }

    /**
     * 获取任务详情
     *
     * @param id 任务ID
     * @return 任务详情VO
     */
    @Override
    public AdminTaskDetailVO getTaskDetail(Long id) {
        TaskVO task = taskService.getTaskDetail(id);
        AdminTaskDetailVO detail = new AdminTaskDetailVO();
        BeanUtil.copyProperties(task, detail);
        return detail;
    }

    /**
     * 下架任务
     *
     * <p>审计说明：本方法<b>刻意不加</b>{@code @Log}。被委托的
     * {@code taskWorkflowService.cancelTaskByAdmin} 已带同 action（TASK_ADMIN_CANCEL）的审计注解，
     * 两者叠加会让一次管理员下架产生两条审计记录。审计保留在业务层，才能覆盖任务状态机的所有入口。</p>
     *
     * @param id 任务ID
     */
    @Override
    public void downTask(Long id) {
        // 复用任务状态机：锁 + 状态校验 + 取消任务 + 清理申请 + 关闭会话 + 通知双方
        taskWorkflowService.cancelTaskByAdmin(id);
    }

    /**
     * 添加任务分类
     *
     * @param categoryDTO 任务分类DTO参数
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "null",
            action = OperationActionEnum.TASK_CATEGORY_ADD, descriptionEL = "'管理员新增任务分类'")
    @CacheEvict(cacheNames = CacheConstants.TASK_CATEGORY_ALL, allEntries = true)
    public void addCategory(AdminTaskCategoryDTO categoryDTO) {
        TaskCategory category = new TaskCategory();
        BeanUtil.copyProperties(categoryDTO, category);
        try {
            categoryService.saveOrUpdate(category);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MessageConstants.CATEGORY_NAME_EXIST);
        }
    }

    /**
     * 删除任务分类
     * @param id 任务分类ID
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "#id",
            action = OperationActionEnum.TASK_CATEGORY_DELETE, descriptionEL = "'管理员删除任务分类 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.TASK_CATEGORY_ALL, allEntries = true)
    public void deleteCategory(Long id) {
        // 校验分类下是否存在任务，存在则拒绝删除（避免悬空引用）
        Long taskCount = taskService.lambdaQuery()
                .eq(Task::getCategoryId, id)
                .count();
        if (taskCount > 0) {
            throw new BusinessException(MessageConstants.CATEGORY_HAS_TASKS);
        }
        categoryService.removeById(id);
    }

    /**
     * 修改任务分类
     * @param id 任务分类ID
     * @param categoryDTO 任务分类DTO参数
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.CATEGORY,
            targetIdEL = "#id",
            action = OperationActionEnum.TASK_CATEGORY_UPDATE, descriptionEL = "'管理员修改任务分类 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.TASK_CATEGORY_ALL, allEntries = true)
    public void updateCategory(Long id, AdminTaskCategoryDTO categoryDTO) {
        TaskCategory category = categoryService.getById(id);
        if (category == null) {
            throw new BusinessException(MessageConstants.CATEGORY_NOT_FOUND);
        }
        BeanUtil.copyProperties(categoryDTO, category);
        categoryService.updateById(category);
    }

    /**
     * 分页查询全站任务评价（按评价人/被评价人/评分/时间筛选）
     *
     * @param queryDTO 查询参数
     * @return 评价分页结果
     */
    @Override
    public PageResult<EvaluationVO> getEvaluationList(AdminEvaluationQueryDTO queryDTO) {
        Page<TaskEvaluation> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<TaskEvaluation> evaluationPage = taskEvaluationService.lambdaQuery()
                .eq(queryDTO.getFromUid() != null, TaskEvaluation::getFromUid, queryDTO.getFromUid())
                .eq(queryDTO.getToUid() != null, TaskEvaluation::getToUid, queryDTO.getToUid())
                .ge(queryDTO.getMinScore() != null, TaskEvaluation::getScore, queryDTO.getMinScore())
                .le(queryDTO.getMaxScore() != null, TaskEvaluation::getScore, queryDTO.getMaxScore())
                .ge(queryDTO.getStartCreateTime() != null, TaskEvaluation::getCreateTime, queryDTO.getStartCreateTime())
                .le(queryDTO.getEndCreateTime() != null, TaskEvaluation::getCreateTime, queryDTO.getEndCreateTime())
                .orderByDesc(TaskEvaluation::getCreateTime)
                .page(page);
        if (evaluationPage.getRecords().isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }
        List<EvaluationVO> itemList = evaluationPage.getRecords().stream()
                .map(evaluation -> {
                    EvaluationVO item = new EvaluationVO();
                    BeanUtil.copyProperties(evaluation, item);
                    return item;
                }).toList();
        return new PageResult<>(evaluationPage.getTotal(), itemList);
    }

    /**
     * 删除评价（处理辱骂/骚扰等违规评价）
     *
     * @param id 评价ID
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#id",
            action = OperationActionEnum.TASK_EVALUATION_DELETE, descriptionEL = "'管理员删除任务评价 id=' + #id")
    public void deleteEvaluation(Long id) {
        if (!taskEvaluationService.removeById(id)) {
            throw new BusinessException(MessageConstants.EVALUATION_NOT_FOUND);
        }
    }
}
