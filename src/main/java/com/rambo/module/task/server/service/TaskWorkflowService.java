package com.rambo.module.task.server.service;

import org.springframework.web.multipart.MultipartFile;

public interface TaskWorkflowService {

    /**
     * 申请任务（编排层：校验任务 + 创建申请；并发防重由防重复注解与唯一索引兜底）
     */
    void applyForTask(Long taskId, String reason);

    /**
     * 同意申请（编排层：更新申请状态 + 拒绝其他申请 + 更新任务状态为进行中）
     */
    void acceptApplication(Long applicationId);

    /**
     * 拒绝申请（编排层：仅更新申请状态）
     */
    void rejectApplication(Long applicationId);

    /**
     * 取消任务（编排层：取消任务 + 拒绝该任务下所有待处理申请）
     */
    void cancelTask(Long taskId);

    /**
     * 管理员强制取消任务（编排层：校验任务 + 锁 + 取消任务 + 清理申请 + 关闭会话 + 通知双方）
     * 与 cancelTask 的区别：不校验发布者身份，管理员可取消任意非终态任务
     */
    void cancelTaskByAdmin(Long taskId);

    /**
     * 申请者确认完成任务（编排层：上传证据 + 更新申请状态 + 更新任务状态为待确认）
     */
    void completeTaskApplication(Long applicationId, MultipartFile completeEvidence);

    /**
     * 发布者确认完成任务（编排层：更新任务状态为已完成 + 生成订单）
     */
    void confirmCompleteTask(Long taskId);
}