package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskApplicationQueryDTO;
import com.rambo.module.task.pojo.entity.TaskApplication;
import com.rambo.module.task.pojo.vo.TaskApplicationVO;

public interface TaskApplicationService extends IService<TaskApplication> {

    /**
     * 查询指定任务的申请列表
     */
    PageResult<TaskApplicationVO> taskApplicationList(Long taskId, TaskApplicationQueryDTO taskApplicationQueryDTO);

    /**
     * 申请者取消任务申请（仅修改申请状态）
     */
    void cancelTaskApplication(Long id);

    /**
     * 任务取消时清理所有非终态申请：
     * 待处理申请 → 已拒绝；已接受申请 → 已取消（接单关系随任务取消而失效）
     */
    void cancelTaskApplications(Long taskId);

    /**
     * 获取指定任务中状态为“已完成”的申请（即申请人已提交完成证据的记录）
     */
    TaskApplication getCompletedApplication(Long taskId);

    /**
     * 检查用户是否已经申请过该任务（防重）
     */
    boolean existsByTaskAndApplicant(Long taskId, Long applicantId);

    /**
     * 拒绝同一任务下的其他申请（同意申请时调用）
     */
    void rejectOtherApplications(Long taskId, Long excludeApplicationId);
}