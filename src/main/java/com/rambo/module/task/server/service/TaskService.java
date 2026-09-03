package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskDTO;
import com.rambo.module.task.pojo.dto.TaskMyQueryDTO;
import com.rambo.module.task.pojo.dto.TaskQueryDTO;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.pojo.vo.TaskVO;
import jakarta.validation.Valid;

public interface TaskService extends IService<Task> {
    /**
     * 发布任务
     *
     * @param taskDTO 任务DTO，包含任务信息
     */
    void publishTask(@Valid TaskDTO taskDTO);

    /**
     * 更新任务
     *
     * @param taskId  任务ID
     * @param taskDTO 任务DTO，包含任务信息
     */
    void updateTask(Long taskId, @Valid TaskDTO taskDTO);

    /**
     * 获取用户发布的任务
     *
     * @param taskMyQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含用户发布的任务列表
     */
    PageResult<TaskVO> getMyPublishedTasks(TaskMyQueryDTO taskMyQueryDTO);

    /**
     * 获取用户接受的任务
     *
     * @param taskMyQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含用户接受的任务列表
     */
    PageResult<TaskVO> getMyAcceptedTasks(TaskMyQueryDTO taskMyQueryDTO);

    /**
     * 获取所有任务
     *
     * @param taskQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含所有任务列表
     */
    PageResult<TaskVO> getAllTasks(TaskQueryDTO taskQueryDTO);

    /**
     * 获取任务详情
     *
     * @param taskId 任务ID
     * @return 任务详情VO
     */
    TaskVO getTaskDetail(Long taskId);

    /**
     * 判断用户是否是任务参与人
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 是否是任务参与人
     */
    boolean isParticipant(Long taskId, Long userId);
}