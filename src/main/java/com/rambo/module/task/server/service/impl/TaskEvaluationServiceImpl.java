package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.MessageConstants;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.task.enums.TaskStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.context.IdHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.task.pojo.dto.EvaluationDTO;
import com.rambo.module.task.pojo.dto.EvaluationQueryDTO;
import com.rambo.module.task.pojo.entity.TaskEvaluation;
import com.rambo.module.task.pojo.entity.TaskOrder;
import com.rambo.module.task.pojo.vo.EvaluationVO;
import com.rambo.module.task.server.mapper.TaskEvaluationMapper;
import com.rambo.module.task.server.mapper.TaskOrderMapper;
import com.rambo.module.task.server.service.TaskEvaluationService;
import com.rambo.module.task.server.service.TaskService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class TaskEvaluationServiceImpl extends ServiceImpl<TaskEvaluationMapper, TaskEvaluation> implements TaskEvaluationService {

    @Resource
    private TaskOrderMapper taskOrderMapper;

    @Resource
    private TaskService taskService;

    @Resource
    private NotificationSender notificationSender;

    /**
     * 发布评价
     *
     * @param dto 评价DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#evaluationDTO.getOrderId()",
            action = OperationActionEnum.EVALUATION_ADD, descriptionEL = "'发布评价'")
    public void addEvaluation(EvaluationDTO evaluationDTO) {
        Long currentUserId = IdHolder.getId();
        // 1. 查询订单是否存在
        TaskOrder order = taskOrderMapper.selectById(evaluationDTO.getOrderId());
        if (order == null) {
            throw new BusinessException(MessageConstants.ORDER_NOT_FOUND);
        }

        // 2. 校验任务必须是【已完成】状态才能评价
        boolean finished = taskService.lambdaQuery()
                .eq(com.rambo.module.task.pojo.entity.Task::getId, order.getTaskId())
                .eq(com.rambo.module.task.pojo.entity.Task::getStatus, TaskStatus.COMPLETED)
                .exists();

        if (!finished) {
            throw new BusinessException(MessageConstants.TASK_NOT_FINISHED);
        }

        // 3. 只能评价自己参与的任务
        if (!order.getPublisherId().equals(currentUserId) && !order.getReceiverId().equals(currentUserId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 4. 判断评价人和被评价人
        Long toUid = order.getPublisherId().equals(currentUserId)
                ? order.getReceiverId()
                : order.getPublisherId();

        // 2. 检查是否已评价过该订单
        boolean existed = lambdaQuery()
                .eq(TaskEvaluation::getOrderId, evaluationDTO.getOrderId())
                .eq(TaskEvaluation::getFromUid, currentUserId)
                .exists();
        if (existed) {
            throw new BusinessException(MessageConstants.EVALUATION_EXISTED);
        }

        // 5. 保存评价
        TaskEvaluation taskEvaluation = new TaskEvaluation();
        taskEvaluation.setOrderId(evaluationDTO.getOrderId());
        taskEvaluation.setFromUid(currentUserId);
        taskEvaluation.setToUid(toUid);
        taskEvaluation.setScore(evaluationDTO.getScore());
        taskEvaluation.setContent(evaluationDTO.getContent());
        try {
            save(taskEvaluation);
            // 通知被评价人
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(toUid)
                    .type(NotificationType.TASK_EVALUATE)
                    .content(MessageConstants.TASK_EVALUATION_SUCCESS)
                    .refId(evaluationDTO.getOrderId())
                    .build());
            log.info("用户 {} 发布对订单 {} 的评价成功", currentUserId, evaluationDTO.getOrderId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MessageConstants.EVALUATION_EXISTED);
        }
    }

    /**
     * 根据订单ID查询评价列表
     *
     * @param orderId 订单ID
     * @param queryDTO     查询参数
     * @return 评价列表
     */
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#orderId",
            action = OperationActionEnum.EVALUATION_VIEW, descriptionEL = "'查看任务评价 orderId=' + #orderId")
    @Override
    public PageResult<EvaluationVO> getByOrderId(Long orderId, EvaluationQueryDTO queryDTO) {
        // 越权防护（IDOR）：仅订单参与方（发布者/接单者）可查看评价，防止遍历 orderId 窃取他人评价。
        // 注意：不启用 @Cacheable —— 原缓存 key 仅含 orderId，命中缓存会绕过本校验，且不同分页参数会串页。
        TaskOrder order = taskOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(MessageConstants.ORDER_NOT_FOUND);
        }
        Long currentUserId = IdHolder.getId();
        if (!order.getPublisherId().equals(currentUserId) && !order.getReceiverId().equals(currentUserId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 分页参数
        Page<TaskEvaluation> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        // 分页查询评价
        IPage<TaskEvaluation> iPage = lambdaQuery()
                .eq(TaskEvaluation::getOrderId, orderId)
                .orderByDesc(TaskEvaluation::getCreateTime)
                .page(page);

        // 无数据直接返回空
        if (iPage.getTotal() == 0) {
            return new PageResult<>(0, Collections.emptyList());
        }

        // 流式转换 VO
        List<EvaluationVO> itemList = iPage.getRecords().stream()
                .map(evaluation -> BeanUtil.copyProperties(evaluation, EvaluationVO.class))
                .toList();

        log.info("查看任务评价 orderId={}", orderId);
        return new PageResult<>(iPage.getTotal(), itemList);
    }
}