package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.context.IdHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskOrderQueryDTO;
import com.rambo.module.task.pojo.entity.TaskEvaluation;
import com.rambo.module.task.pojo.entity.TaskOrder;
import com.rambo.module.task.pojo.vo.TaskOrderVO;
import com.rambo.module.task.server.mapper.TaskOrderMapper;
import com.rambo.module.task.server.service.TaskEvaluationService;
import com.rambo.module.task.server.service.TaskOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskOrderServiceImpl extends ServiceImpl<TaskOrderMapper, TaskOrder>
        implements TaskOrderService {

    @Resource
    private TaskEvaluationService taskEvaluationService;

    /**
     * 自动创建订单（内部事务调用，不对外暴露）
     *
     * @param taskId      任务ID
     * @param publisherId 发布者ID
     * @param receiverId  接单者ID
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "null",
            action = OperationActionEnum.ORDER_CREATE, descriptionEL = "'生成订单 taskId=' + #taskId")
    @Transactional
    public void autoCreateOrder(Long taskId, Long publisherId, Long receiverId) {
        if (taskId == null || publisherId == null || receiverId == null) {
            throw new BusinessException(MessageConstants.PARAM_ERROR);
        }
        boolean exists = lambdaQuery().eq(TaskOrder::getTaskId, taskId).exists();
        if (exists) {
            log.warn(MessageConstants.TASK_ORDER_EXISTS);
            return;
        }
        TaskOrder order = new TaskOrder();
        order.setTaskId(taskId);
        order.setPublisherId(publisherId);
        order.setReceiverId(receiverId);
        save(order);
        log.info("用户 {} 生成任务 {} 的订单成功", publisherId, taskId);
    }

    /**
     * 分页查询我的订单（发布者/接单者都能看自己的）
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.ORDER_VIEW, descriptionEL = "'查看我的订单'")
    public PageResult<TaskOrderVO> getMyOrderPage(TaskOrderQueryDTO queryDTO) {
        Long userId = IdHolder.getId();
        log.info("用户 {} 查看我的订单列表", userId);

        // 分页参数
        Page<TaskOrder> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        // 分页查询
        IPage<TaskOrder> iPage = page(page,
                Wrappers.lambdaQuery(TaskOrder.class)
                        .and(w -> w.eq(TaskOrder::getPublisherId, userId)
                                .or()
                                .eq(TaskOrder::getReceiverId, userId))
                        .orderByDesc(TaskOrder::getCreateTime)
        );

        if (iPage.getTotal() == 0) {
            return new PageResult<>(0, new ArrayList<>());
        }

        // 一次性批量查询评价
        // 1. 先收集订单ID
        Set<Long> orderIds = iPage.getRecords().stream()
                .map(TaskOrder::getId)
                .collect(Collectors.toSet());

        // 2. 最后再查询评价
        Set<Long> evaluatedOrderIds = taskEvaluationService.lambdaQuery()
                .in(TaskEvaluation::getOrderId, orderIds)
                .eq(TaskEvaluation::getFromUid, userId)
                .list()
                .stream()
                .map(TaskEvaluation::getOrderId)
                .collect(Collectors.toSet());

        // 流式转 VO
        List<TaskOrderVO> itemList = iPage.getRecords().stream()
                .map(order -> {
                    TaskOrderVO item = BeanUtil.copyProperties(order, TaskOrderVO.class);
                    item.setEvaluated(evaluatedOrderIds.contains(order.getId()));
                    return item;
                })
                .toList();

        PageResult<TaskOrderVO> result = new PageResult<>(iPage.getTotal(), itemList);
        log.info("用户 {} 查看自己的订单列表", userId);
        return result;
    }
}