package com.rambo.module.operationlog.server.service;

import com.rambo.module.operationlog.pojo.entity.OperationLog;

public interface IOperationLogService {
    void saveLog(OperationLog operationLog);
}
