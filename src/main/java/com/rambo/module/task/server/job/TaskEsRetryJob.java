package com.rambo.module.task.server.job;

import cn.hutool.core.bean.BeanUtil;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsSyncRetry;
import com.rambo.infrastructure.search.EsSyncRetryService;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.infrastructure.storage.OssAsyncUtil;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.server.service.TaskService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * XXL-JOB：重试任务 ES 索引同步（t_es_sync_retry 中 dataType=task 的记录）
 *
 * <p>实体存在 → 同步索引；实体已被删除 → 清除 ES 数据 + 异步删除 OSS 文件；
 * 单条失败 → 重试次数 +1，超 5 次终止。</p>
 */
@Slf4j
@Component
public class TaskEsRetryJob {

    @Resource
    private EsSyncRetryService esRetryService;
    @Resource
    private TaskService taskService;
    @Resource
    private EsUtil esUtil;
    @Resource
    private OssAsyncUtil ossAsyncUtil;

    @XxlJob("taskEsRetryJob")
    public void execute() {
        // 1. 查询任务类型的待重试数据
        List<EsSyncRetry> list = esRetryService.getWaitRetryList("task");
        if (list.isEmpty()) {
            return;
        }

        // 2. 逐条重试
        for (EsSyncRetry retry : list) {
            try {
                boolean dataExists = true;
                // 查任务
                Task task = taskService.getById(retry.getDataId());

                // 判断任务是否存在，不存在则删除 ES 数据
                if (task == null) {
                    dataExists = false;
                } else {
                    EsDTO esDTO = BeanUtil.copyProperties(task, EsDTO.class);
                    // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
                    esDTO.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
                    esUtil.saveTask(esDTO);
                }

                // 实体不存在：删除 ES 数据 + 异步删除 OSS 文件
                if (!dataExists) {
                    esUtil.deleteTask(retry.getDataId());
                    String fileUrls = retry.getFileUrls();
                    if (StringUtils.hasText(fileUrls)) {
                        List<String> fileList = Arrays.asList(fileUrls.split(","));
                        ossAsyncUtil.deleteFilesAsync(fileList, "task", retry.getDataId());
                    }
                }

                // 成功：标记成功
                esRetryService.markSuccess(retry.getId());
            } catch (Exception e) {
                // 失败次数+1，超过5次停止
                esRetryService.incrRetryCount(retry.getId(), e.getMessage());
                log.error("任务ES重试失败，dataId：{}", retry.getDataId(), e);
            }
        }

        XxlJobHelper.handleSuccess("处理完成，共重试 " + list.size() + " 条任务同步记录");
    }
}
