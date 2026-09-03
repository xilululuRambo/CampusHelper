package com.rambo.module.operationlog.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.common.result.PageResult;
import com.rambo.module.operationlog.pojo.entity.OperationLog;
import com.rambo.module.operationlog.pojo.dto.OperationLogQueryDTO;
import com.rambo.module.operationlog.pojo.vo.OperationLogVO;

public interface OperationLogService extends IService<OperationLog> {

    /**
     * 分页查询操作日志（仅管理员可调用）
     *
     * @param queryDTO 过滤条件 + 分页参数
     * @return 操作日志分页结果
     */
    PageResult<OperationLogVO> pageQuery(OperationLogQueryDTO queryDTO);
}
