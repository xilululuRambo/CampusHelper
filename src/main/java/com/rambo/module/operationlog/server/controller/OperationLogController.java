package com.rambo.module.operationlog.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.operationlog.pojo.dto.OperationLogQueryDTO;
import com.rambo.module.operationlog.pojo.vo.OperationLogVO;
import com.rambo.module.operationlog.server.service.OperationLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operationLog")
@Slf4j
@Validated
@Tag(name = "操作日志接口")
public class OperationLogController {
    @Resource
    private OperationLogService operationLogService;

    /**
     * 分页查询操作日志（仅管理员/超级管理员可见）
     *
     * @param queryDTO 过滤条件 + 分页参数
     * @return 操作日志分页结果
     */
    @GetMapping("/page")
    public Result<PageResult<OperationLogVO>> page(OperationLogQueryDTO queryDTO) {
        log.info("查询操作日志:{}", queryDTO);
        return Result.success(operationLogService.pageQuery(queryDTO));
    }
}
