package com.rambo.module.operationlog.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.module.operationlog.server.service.IOperationLogService;
import com.rambo.common.constants.CodeConstants;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.result.PageResult;
import com.rambo.common.context.RoleHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.operationlog.enums.OperatorRoleEnum;
import com.rambo.module.operationlog.pojo.entity.OperationLog;
import com.rambo.module.operationlog.pojo.dto.OperationLogQueryDTO;
import com.rambo.module.operationlog.pojo.vo.OperationLogVO;
import com.rambo.module.operationlog.server.mapper.OperationLogMapper;
import com.rambo.module.operationlog.server.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements OperationLogService, IOperationLogService {

    @Async("taskExecutor")
    @Override
    public void saveLog(OperationLog operationLog) {
        try {
            saveOrUpdate(operationLog);
        } catch (Exception e) {
            log.error("审计日志库失败，traceId: {}", operationLog.getTraceId(), e);
        }
    }

    @Override
    public PageResult<OperationLogVO> pageQuery(OperationLogQueryDTO queryDTO) {
        // 鉴权：操作日志属敏感数据，仅管理员/超级管理员可见
        String role = RoleHolder.getRole();
        if (!EnumConstants.ROLE_ADMIN.equals(role) && !EnumConstants.ROLE_SUPER_ADMIN.equals(role)) {
            throw new BusinessException(CodeConstants.FORBIDDEN, "无权限查看操作日志");
        }

        // code -> 枚举（无效 code 忽略，不参与查询）
        OperatorRoleEnum roleEnum = queryDTO.getOperatorRole() != null ? OperatorRoleEnum.getByCode(queryDTO.getOperatorRole()) : null;
        OperationModuleEnum moduleEnum = queryDTO.getModule() != null ? OperationModuleEnum.getByCode(queryDTO.getModule()) : null;
        OperationTargetTypeEnum targetTypeEnum = queryDTO.getTargetType() != null ? OperationTargetTypeEnum.getByCode(queryDTO.getTargetType()) : null;
        OperationActionEnum actionEnum = queryDTO.getAction() != null ? OperationActionEnum.getByCode(queryDTO.getAction()) : null;

        Page<OperationLog> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<OperationLog> logPage = this.lambdaQuery()
                .eq(queryDTO.getOperatorId() != null, OperationLog::getOperatorId, queryDTO.getOperatorId())
                .eq(roleEnum != null, OperationLog::getOperatorRole, roleEnum)
                .eq(moduleEnum != null, OperationLog::getModule, moduleEnum)
                .eq(targetTypeEnum != null, OperationLog::getTargetType, targetTypeEnum)
                .eq(actionEnum != null, OperationLog::getAction, actionEnum)
                .eq(queryDTO.getResult() != null, OperationLog::getResult, queryDTO.getResult())
                .like(queryDTO.getKeyword() != null, OperationLog::getDescription, queryDTO.getKeyword())
                .ge(queryDTO.getStartCreateTime() != null, OperationLog::getCreateTime, queryDTO.getStartCreateTime())
                .le(queryDTO.getEndCreateTime() != null, OperationLog::getCreateTime, queryDTO.getEndCreateTime())
                .orderByDesc(OperationLog::getCreateTime)
                .page(page);

        if (logPage.getRecords().isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        List<OperationLogVO> itemList = logPage.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(logPage.getTotal(), itemList);
    }

    private OperationLogVO toVO(OperationLog log) {
        OperationLogVO target = new OperationLogVO();
        // 标量字段直接拷贝（枚举字段在 VO 中拆为 code/name，需手动处理，BeanUtil 会跳过无对应属性的源字段）
        BeanUtil.copyProperties(log, target);
        if (log.getOperatorRole() != null) {
            target.setOperatorRoleCode(log.getOperatorRole().getCode());
            target.setOperatorRoleName(log.getOperatorRole().getDescription());
        }
        if (log.getModule() != null) {
            target.setModuleCode(log.getModule().getCode());
            target.setModuleName(log.getModule().getDescription());
        }
        if (log.getTargetType() != null) {
            target.setTargetTypeCode(log.getTargetType().getCode());
            target.setTargetTypeName(log.getTargetType().getDescription());
        }
        if (log.getAction() != null) {
            target.setActionCode(log.getAction().getCode());
            target.setActionName(log.getAction().getDescription());
        }
        return target;
    }
}
