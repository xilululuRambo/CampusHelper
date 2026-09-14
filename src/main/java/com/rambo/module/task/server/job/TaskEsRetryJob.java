package com.rambo.module.task.server.job;

import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.search.EsSyncOutbox;
import com.rambo.infrastructure.search.EsSyncOutboxService;
import com.rambo.module.task.server.service.impl.TaskEsSyncService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * XXL-JOB：任务 ES 同步补偿任务。
 *
 * <p>扫描 t_es_sync_outbox 中 dataType=task 且 PENDING 的意图，逐条调用
 * {@link TaskEsSyncService#dispatch(Long)} 回源任务并同步。这是事务性 Outbox
 * 主链路的兜底：异步派发失败（进程崩溃、线程池拒绝、ES 短暂不可用）都由这里收敛。</p>
 */
@Slf4j
@Component
public class TaskEsRetryJob {

    /**
     * 单次派发发件箱的最大条数（避免一次拉取过多导致长任务）
     */
    private static final int OUTBOX_BATCH_LIMIT = 200;

    @Resource
    private EsSyncOutboxService esSyncOutboxService;
    @Resource
    private TaskEsSyncService taskEsSyncService;

    @XxlJob("taskEsRetryJob")
    public void execute() {
        int dispatched = dispatchOutbox();
        XxlJobHelper.handleSuccess("处理完成，发件箱派发 " + dispatched + " 条");
    }

    /**
     * 扫描并派发发件箱待同步行；单条失败由派发逻辑内部记为重试，不中断整批。
     *
     * @return 本次扫描到的待派发条数
     */
    private int dispatchOutbox() {
        List<EsSyncOutbox> pending = esSyncOutboxService.getWaitList(PrefixConstants.TASK_TYPE, OUTBOX_BATCH_LIMIT);
        for (EsSyncOutbox row : pending) {
            try {
                // 按 outbox 行 id 精确派发：每行 id 唯一，对应一次具体的同步意图
                taskEsSyncService.dispatch(row.getId());
            } catch (Exception e) {
                log.error("任务发件箱派发异常，outboxId：{}", row.getId(), e);
            }
        }
        return pending.size();
    }
}
