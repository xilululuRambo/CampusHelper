package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsSyncRetryService;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.task.pojo.entity.Task;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 任务 ES 索引同步组件（业务层编排：任务实体 → EsDTO → EsUtil 写入，失败写重试表）。
 *
 * <p>由基础设施层 {@code EsAsyncUtil} 拆出并下沉到业务层：任务同步逻辑依赖业务实体 Task，
 * 属业务编排而非通用能力，归任务模块所有，使基础设施层对业务模块保持零依赖。</p>
 */
@Component
@Slf4j
public class TaskEsSyncService {
    @Resource
    private EsSyncRetryService esSyncRetryService;

    @Resource
    private EsUtil esUtil;

    /**
     * 统一异步线程池（AsyncConfig 的 taskExecutor）：
     * 替代 ForkJoinPool.commonPool——保 MDC traceId、避免被并行流/其他任务饱和。
     */
    @Resource(name = "taskExecutor")
    private Executor taskExecutor;

    /**
     * 异步同步任务到ES
     *
     * @param task 任务实体类
     */
    public void syncToEsAsync(Task task) {
        // 版本号在调用线程取值（见 GoodsEsSyncService 说明），防异步乱序覆盖
        long version = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                EsDTO esDTO = BeanUtil.copyProperties(task, EsDTO.class);
                // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
                esDTO.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
                esDTO.setUpdateTime(version);
                esUtil.saveTask(esDTO);
            } catch (ElasticsearchException e) {
                if (e.status() == 409) {
                    // 旧版本数据被 ES 拒绝（防乱序的预期行为），忽略
                    log.debug("ES 版本冲突（旧数据），忽略覆盖 taskId={}, version={}", task.getId(), version);
                } else {
                    log.error("ES 索引失败，taskId={}，等待定时任务补偿", task.getId(), e);
                    esSyncRetryService.addRetry(task.getId(), PrefixConstants.TASK_TYPE, null, e.getMessage());
                }
            } catch (Exception e) {
                // 覆盖 IOException 之外的所有异常，修复"异常静默穿透、重试表不写"的问题
                log.error("ES 索引失败，taskId={}，等待定时任务补偿", task.getId(), e);
                esSyncRetryService.addRetry(task.getId(), PrefixConstants.TASK_TYPE, null, e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 异步删除任务ES
     *
     * @param taskId 任务ID
     */
    public void deleteTaskFromEsAsync(Long taskId) {
        CompletableFuture.runAsync(() -> {
            try {
                esUtil.deleteTask(taskId);
            } catch (Exception e) {
                log.error("ES任务删除失败 id:{}", taskId, e);
                // 失败写入重试表
                esSyncRetryService.addRetry(taskId, PrefixConstants.TASK_TYPE, null, e.getMessage());
            }
        }, taskExecutor);
    }
}
