package com.rambo.module.task.server.service.impl;

import com.rambo.common.annotation.PreventDuplicate;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.chat.enums.MessageType;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.task.enums.TaskApplyStatus;
import com.rambo.module.task.enums.TaskStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.infrastructure.database.TransactionUtils;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.module.chat.server.service.ChatService;
import com.rambo.module.chat.server.service.ChatSessionService;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.pojo.entity.TaskApplication;
import com.rambo.module.task.server.service.TaskApplicationService;
import com.rambo.module.task.server.service.TaskOrderService;
import com.rambo.module.task.server.service.TaskService;
import com.rambo.module.task.server.service.TaskWorkflowService;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TaskWorkflowServiceImpl implements TaskWorkflowService {

    @Resource
    private TaskService taskService;
    @Resource
    private TaskApplicationService taskApplicationService;
    @Resource
    private TaskOrderService taskOrderService;
    @Resource
    private LockClient lockClient;
    @Resource
    private AliyunOssUtil aliyunOssUtil;
    @Resource
    private NotificationSender notificationSender;
    @Resource
    private ChatSessionService chatSessionService;
    @Resource
    private ChatService chatService;
    @Resource
    private CacheClient cacheClient;

    /**
     * 申请任务（编排层：校验任务 + 创建申请；并发防重由防重复注解与唯一索引兜底）
     *
     * @param taskId 任务ID
     * @param reason 申请原因
     */
    @Override
    @Transactional
    @PreventDuplicate(scene = "applyForTask")
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId", action = OperationActionEnum.TASK_APPLY, descriptionEL = "'申请任务, taskId=' + #taskId")
    public void applyForTask(Long taskId, String reason) {
        Long userId = IdHolder.getId();

        // 1. 校验任务
        Task task = taskService.getById(taskId);
        if (task == null || task.getStatus() != TaskStatus.PENDING) {
            throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
        }

        // 校验用户是否为任务发布者(不能申请自己发布的任务)
        if (task.getPublisherId().equals(userId)) {
            throw new BusinessException(MessageConstants.TASK_APPLICATION_PUBLISHER_ERROR);
        }

        // 2. 创建申请记录
        TaskApplication application = new TaskApplication();
        application.setTaskId(taskId);
        application.setReason(reason);
        application.setApplicantId(userId);
        application.setStatus(TaskApplyStatus.PENDING_APPLICATION);

        // 3. 保存申请记录
        try {
            taskApplicationService.save(application);

            // 4. 通知任务发布者
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(task.getPublisherId())
                    .type(NotificationType.TASK_NEW_APPLY)
                    .content(MessageConstants.TASK_APPLICATION_SUCCESS)
                    .refId(application.getId())
                    .build());

        } catch (DuplicateKeyException e) {
            TaskApplication existing = taskApplicationService.lambdaQuery()
                    .eq(TaskApplication::getTaskId, taskId)
                    .eq(TaskApplication::getApplicantId, userId)
                    .one();
            if (existing.getStatus() == TaskApplyStatus.REJECTED_APPLICATION) {
                throw new BusinessException(MessageConstants.TASK_APPLICATION_REJECTED_ERROR);
            }
            throw new BusinessException(MessageConstants.TASK_APPLICATION_DUPLICATE_ERROR);
        }
        log.info("用户 {} 申请任务成功，taskId={}", userId, taskId);
    }

    /**
     * 发布者同意任务申请（编排层：校验申请 + 锁 + 更新状态）
     *
     * @param applicationId 申请ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.APPLICATION,
            targetIdEL = "#applicationId", action = OperationActionEnum.TASK_APPLY_ACCEPT, descriptionEL = "'同意任务申请, applicationId=' + #applicationId")
    public void acceptApplication(Long applicationId) {
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + getTaskIdByApplication(applicationId);
        boolean locked = false;
        try {
            // 尝试获取锁，超时时间为3秒
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;

            // 校验申请是否存在且状态为待处理
            TaskApplication app = taskApplicationService.getById(applicationId);
            if (app == null || app.getStatus() != TaskApplyStatus.PENDING_APPLICATION) {
                throw new BusinessException(MessageConstants.TASK_APPLICATION_PROCESS_ERROR);
            }

            // 校验任务是否存在
            Task task = taskService.getById(app.getTaskId());
            if (task == null || task.getStatus() != TaskStatus.PENDING)
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);

            // 权限校验：只有发布者可以同意申请
            if (!task.getPublisherId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            // 执行状态变更：同意申请
            app.setStatus(TaskApplyStatus.ACCEPTED_APPLICATION);

            // 乐观锁更新是否成功
            boolean updated = taskApplicationService.updateById(app);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 拒绝同一任务下的其他待处理申请
            taskApplicationService.rejectOtherApplications(task.getId(), applicationId);

            // 更新任务状态为进行中
            task.setStatus(TaskStatus.IN_PROGRESS);

            // 更新任务接单者ID
            task.setApplicantId(app.getApplicantId());

            // 乐观锁更新是否成功
            updated = taskService.updateById(task);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }
            // 通知申请人
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(app.getApplicantId())
                    .type(NotificationType.APPLY_AGREE)
                    .content(MessageConstants.TASK_APPLICATION_AGREE_SUCCESS)
                    .refId(applicationId)
                    .build());

            //建立会话（事务提交后才创建，回滚不残留假会话）
            String sessionId = "task_" + task.getId();
            ChatMessage autoMsg = new ChatMessage();
            autoMsg.setSessionId(sessionId);
            autoMsg.setSenderId(task.getPublisherId());  // 卖家作为发送者
            autoMsg.setReceiverId(app.getApplicantId());
            autoMsg.setContent(MessageConstants.TASK_APPLICATION_AGREE_SESSION);
            autoMsg.setMsgType(MessageType.TEXT);  // 文本
            autoMsg.setCreateTime(LocalDateTime.now());
            autoMsg.setIsRead(IsReadStatus.UNREAD);  // 未读
            TransactionUtils.afterCommit(() -> {
                chatSessionService.createIfNotExist(sessionId, task.getPublisherId(), app.getApplicantId());
                chatService.saveMessage(autoMsg);
            });
            log.info("用户 {} 同意任务申请成功，applicationId={}", IdHolder.getId(), applicationId);

        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 发布者拒绝任务申请（编排层：校验申请 + 锁 + 更新状态）
     *
     * @param applicationId 申请ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.APPLICATION,
            targetIdEL = "#applicationId", action = OperationActionEnum.TASK_APPLY_REJECT, descriptionEL = "'拒绝任务申请, applicationId=' + #applicationId")
    public void rejectApplication(Long applicationId) {
        // 1. 先获取申请，得到 taskId
        TaskApplication app = taskApplicationService.getById(applicationId);

        // 校验申请是否存在
        if (app == null) {
            throw new BusinessException(MessageConstants.TASK_APPLICATION_NOT_FOUND);
        }

        Long taskId = app.getTaskId();
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + taskId;
        boolean locked = false;
        try {
            // 获取锁，超时时间为3秒
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            // 重新查询申请（避免状态在获取锁前被改变）
            app = taskApplicationService.getById(applicationId);

            // 校验申请状态是否为待处理
            if (app.getStatus() != TaskApplyStatus.PENDING_APPLICATION) {
                throw new BusinessException(MessageConstants.TASK_APPLICATION_PROCESS_ERROR);
            }

            // 校验任务是否存在
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }

            // 校验任务是否是发布者拒绝申请
            if (!task.getPublisherId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            // 更新申请状态为已拒绝
            app.setStatus(TaskApplyStatus.REJECTED_APPLICATION);

            // 乐观锁更新是否成功
            boolean updated = taskApplicationService.updateById(app);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }
            // 通知申请人
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(app.getApplicantId())
                    .type(NotificationType.APPLY_REJECT)
                    .content(MessageConstants.TASK_APPLICATION_REJECT_SUCCESS)
                    .refId(applicationId)
                    .build());
            log.info("用户 {} 拒绝任务申请成功，applicationId={}", IdHolder.getId(), applicationId);
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 发布者取消任务
     *
     * @param taskId 任务ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId", action = OperationActionEnum.TASK_CANCEL, descriptionEL = "'取消任务, taskId=' + #taskId")
    public void cancelTask(Long taskId) {
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + taskId;
        boolean locked = false;
        try {
            // 获取锁，超时时间为3秒
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;

            //校验任务是否存在
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }

            //校验任务是否是发布者
            if (!task.getPublisherId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            //校验任务状态
            if (task.getStatus() == TaskStatus.WAITING_CONFIRM||task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
                throw new BusinessException(MessageConstants.TASK_CANCEL_STATUS_ERROR);
            }
            // 记录取消前的状态，用于判断是否需要关闭会话
            TaskStatus originalStatus = task.getStatus();

            // 取消任务
            task.setStatus(TaskStatus.CANCELLED);

            // 乐观锁更新是否成功
            boolean updated = taskService.updateById(task);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 清理该任务下所有非终态申请（待处理→已拒绝，已接受→已取消）
            taskApplicationService.cancelTaskApplications(taskId);

            // 任务已进行中（有接单者、会话已建立），取消后关闭双方会话
            // 注意：PENDING 状态取消时无会话（会话在同意申请时才创建），无需关闭
            if (originalStatus == TaskStatus.IN_PROGRESS) {
                // 事务提交后才关闭会话，回滚则会话保持打开
                TransactionUtils.afterCommit(() -> chatSessionService.closeSession("task_" + taskId));
            }
            log.info("用户 {} 取消任务成功，taskId={}", IdHolder.getId(), taskId);
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 管理员强制取消任务（不校验发布者身份，管理员可取消任意非终态任务）
     *
     * @param taskId 任务ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId", action = OperationActionEnum.TASK_ADMIN_CANCEL, descriptionEL = "'管理员取消任务, taskId=' + #taskId")
    public void cancelTaskByAdmin(Long taskId) {
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + taskId;
        boolean locked = false;
        try {
            // 获取锁，超时时间为3秒
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;

            // 校验任务是否存在
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }

            // 终态（已完成/已取消）不可取消
            if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
                throw new BusinessException(MessageConstants.TASK_CANCEL_STATUS_ERROR);
            }
            // 记录取消前的状态，用于判断是否需要关闭会话
            TaskStatus originalStatus = task.getStatus();

            // 取消任务
            task.setStatus(TaskStatus.CANCELLED);

            // 乐观锁更新是否成功
            boolean updated = taskService.updateById(task);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 清理该任务下所有非终态申请（待处理→已拒绝，已接受→已取消）
            taskApplicationService.cancelTaskApplications(taskId);

            // 任务已进行中（有接单者、会话已建立），取消后关闭双方会话
            if (originalStatus == TaskStatus.IN_PROGRESS || originalStatus == TaskStatus.WAITING_CONFIRM) {
                // 事务提交后才关闭会话，回滚则会话保持打开
                TransactionUtils.afterCommit(() -> chatSessionService.closeSession("task_" + taskId));
            }

            // 通知任务发布者
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(task.getPublisherId())
                    .type(NotificationType.TASK_CANCEL)
                    .content(EnumConstants.ADMIN_NOTIFICATION_TYPE_TASK_CANCEL)
                    .refId(taskId)
                    .build());

            // 通知任务接单者（任务未被接单时为 null，判空跳过）
            if (task.getApplicantId() != null) {
                notificationSender.sendAsync(NotificationMessage.builder()
                        .userId(task.getApplicantId())
                        .type(NotificationType.TASK_CANCEL)
                        .content(EnumConstants.ADMIN_NOTIFICATION_TYPE_TASK_CANCEL_APPLICATION)
                        .refId(taskId)
                        .build());
            }
            log.info("管理员 {} 取消任务成功，taskId={}", IdHolder.getId(), taskId);
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.APPLICATION,
            targetIdEL = "#applicationId", action = OperationActionEnum.TASK_SUBMIT_COMPLETE, descriptionEL = "'提交完成任务证据, applicationId=' + #applicationId")
    public void completeTaskApplication(Long applicationId, MultipartFile completeEvidence) {
        validateEvidence(completeEvidence);
        // 1. 校验申请是否存在
        TaskApplication app = taskApplicationService.getById(applicationId);
        if (app == null) throw new BusinessException(MessageConstants.TASK_APPLICATION_NOT_FOUND);

        Long taskId = app.getTaskId();

        // 2. 加任务锁，防止并发完成
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + taskId;
        boolean locked = false;
        try {
            // 获取锁，超时时间为3秒
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            //校验申请是否存在
            app = taskApplicationService.getById(applicationId);
            if (app == null) throw new BusinessException(MessageConstants.TASK_APPLICATION_NOT_FOUND);

            //校验申请是否是当前用户
            if (!app.getApplicantId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            //校验申请状态是否是已接受
            if (app.getStatus() != TaskApplyStatus.ACCEPTED_APPLICATION) {
                throw new BusinessException(MessageConstants.TASK_APPLICATION_PROCESS_ERROR);
            }

            //校验任务是否存在且状态是否是进行中
            Task task = taskService.getById(app.getTaskId());
            if (task == null || task.getStatus() != TaskStatus.IN_PROGRESS) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }

            // 上传证据到OSS
            if (!completeEvidence.isEmpty()) {
                String evidenceUrl = aliyunOssUtil.upload(completeEvidence);
                app.setCompleteEvidence(evidenceUrl);
                // 事务回滚时删除刚上传的孤儿文件
                final String uploaded = evidenceUrl;
                TransactionUtils.onRollback(() -> aliyunOssUtil.deleteFile(uploaded));
            }

            // 更新申请状态为已完成
            app.setCompleteTime(LocalDateTime.now());
            app.setStatus(TaskApplyStatus.COMPLETED_APPLICATION);

            // 乐观锁更新是否成功
            boolean updated = taskApplicationService.updateById(app);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 任务状态变为待确认完成
            task.setStatus(TaskStatus.WAITING_CONFIRM);

            // 乐观锁更新是否成功
            updated = taskService.updateById(task);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 通知任务发布者
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(task.getPublisherId())
                    .type(NotificationType.TASK_COMPLETE)
                    .content(MessageConstants.TASK_APPLICATION_COMPLETE_SUCCESS)
                    .refId(taskId)
                    .build());
            log.info("用户 {} 提交完成任务证据成功，applicationId={}", IdHolder.getId(), applicationId);
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 发布者确认完成任务
     *
     * @param taskId 任务ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId", action = OperationActionEnum.TASK_CONFIRM_COMPLETE, descriptionEL = "'确认完成任务, taskId=' + #taskId")
    public void confirmCompleteTask(Long taskId) {
        String lockKey = PrefixConstants.TASK_AGREE_LOCK_PREFIX + taskId;
        boolean locked = false;
        try {
            if (!lockClient.tryLock(lockKey, NumConstants.TASK_AGREE_LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;

            //校验任务是否存在
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }

            //校验任务是否是发布者
            if (!task.getPublisherId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            //校验任务状态是否是待确认完成状态
            if (task.getStatus() != TaskStatus.WAITING_CONFIRM) {
                throw new BusinessException(MessageConstants.TASK_STATUS_ERROR);
            }

            // 校验任务是否有已完成的申请
            TaskApplication completedApp = taskApplicationService.getCompletedApplication(taskId);

            //校验任务申请是否存在
            if (completedApp == null) {
                throw new BusinessException(MessageConstants.TASK_APPLICATION_NOT_FOUND);
            }

            // 任务最终完成
            task.setStatus(TaskStatus.COMPLETED);

            // 乐观锁更新是否成功
            boolean updated = taskService.updateById(task);
            if (!updated) {
                // 任务已被其他线程修改，需要抛出业务异常让用户感知
                throw new BusinessException(MessageConstants.TASK_STATUS_CHANGED);
            }

            // 生成订单
            taskOrderService.autoCreateOrder(taskId, task.getPublisherId(), completedApp.getApplicantId());
            // 通知申请人
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(completedApp.getApplicantId())
                    .type(NotificationType.TASK_COMPLETE)
                    .content(MessageConstants.TASK_COMPLETE_SUCCESS)
                    .refId(taskId)
                    .build());

            //结束会话（事务提交后关闭，回滚则会话保持打开）
            String sessionId = "task_" + task.getId();
            // 事务提交后执行外部副作用（关会话 + 排行榜加分），回滚不产生假状态/虚增
            Long applicantId = completedApp.getApplicantId();
            TransactionUtils.afterCommit(() -> {
                chatSessionService.closeSession(sessionId);
                String monthKey = PrefixConstants.TASK_RANK_MONTH + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                cacheClient.zIncrementScore(PrefixConstants.TASK_RANK_TOTAL, String.valueOf(applicantId), 1);
                cacheClient.zIncrementScore(monthKey, String.valueOf(applicantId), 1);
            });

            log.info("用户 {} 确认完成任务成功，taskId={}", IdHolder.getId(), taskId);
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 根据任务申请ID获取任务ID
     *
     * @param applicationId 任务申请ID
     * @return 任务ID
     */
    private Long getTaskIdByApplication(Long applicationId) {
        TaskApplication app = taskApplicationService.getById(applicationId);
        if (app == null) throw new BusinessException(MessageConstants.TASK_APPLICATION_NOT_FOUND);
        return app.getTaskId();
    }

    /**
     * 校验任务完成证据（单文件）
     *
     * @param file 证据文件
     */
    private void validateEvidence(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(MessageConstants.EVIDENCE_EMPTY);
        }
        // 大小
        if (file.getSize() > NumConstants.EVIDENCE_MAX_SIZE) {
            throw new BusinessException(MessageConstants.EVIDENCE_SIZE_ERROR);
        }
        // 格式
        String original = file.getOriginalFilename();
        if (original == null || !original.contains(".")) {
            throw new BusinessException(MessageConstants.EVIDENCE_FORMAT_ERROR);
        }
        String suffix = original.substring(original.lastIndexOf(".")).toLowerCase();
        if (!NumConstants.EVIDENCE_ALLOWED_TYPES.contains(suffix)) {
            throw new BusinessException(MessageConstants.EVIDENCE_FORMAT_ERROR);
        }
    }
}