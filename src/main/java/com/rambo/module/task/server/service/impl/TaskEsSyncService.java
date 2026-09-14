package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.search.AbstractEsOutboxSyncService;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.server.mapper.TaskMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务 ES 索引同步组件（业务层编排：登记 Outbox 意图 → 派发时回源任务 → EsUtil 写入）。
 *
 * <p>同步逻辑收敛到基础设施层基类 {@link AbstractEsOutboxSyncService}，本类只负责
 * 「任务领域」的三件事：数据类型标识、回源加载任务、以及任务索引的读写原语。
 * 由于回源用 {@link TaskMapper}（而非 TaskService），避免了与 TaskServiceImpl 的循环依赖。</p>
 *
 * <p>ES external version 由发件箱行 id 担任——基类 {@link AbstractEsOutboxSyncService#dispatch}
 * 把 row.id 作为 {@code version} 传下来，本类再透传给 {@link EsUtil#saveTask}。</p>
 */
@Component
@Slf4j
public class TaskEsSyncService extends AbstractEsOutboxSyncService {

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private EsUtil esUtil;

    @Override
    protected String dataType() {
        return PrefixConstants.TASK_TYPE;
    }

    @Override
    protected EsDTO loadEsDTO(Long dataId) {
        Task task = taskMapper.selectById(dataId);
        if (task == null) {
            return null;
        }
        EsDTO esDTO = BeanUtil.copyProperties(task, EsDTO.class);
        // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
        esDTO.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
        return esDTO;
    }

    @Override
    protected void saveEsDoc(EsDTO esDTO, long version) throws Exception {
        esUtil.saveTask(esDTO, version);
    }

    @Override
    protected void deleteEsDoc(Long dataId, long version) throws Exception {
        esUtil.deleteTask(dataId, version);
    }

    /**
     * 异步同步任务到ES（对外签名保持不变，内部改为登记事务性 Outbox 意图）。
     *
     * @param task 任务实体类
     */
    public void syncToEsAsync(Task task) {
        enqueueUpsert(task.getId(), null);
    }

    /**
     * 异步删除任务ES（对外签名保持不变，内部改为登记事务性 Outbox 意图）。
     *
     * @param taskId 任务ID
     */
    public void deleteTaskFromEsAsync(Long taskId) {
        enqueueDelete(taskId, null);
    }
}