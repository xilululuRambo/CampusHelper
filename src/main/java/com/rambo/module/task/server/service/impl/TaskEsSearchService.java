package com.rambo.module.task.server.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.result.PageResult;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.task.enums.TaskStatus;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 任务 ES 搜索组件（业务层）：关键词分页搜索属于任务业务规则
 * （仅检索待接单、分类/奖励/时间过滤、排序策略），从基础设施层 {@code EsUtil} 下沉到业务模块，
 * 使基础设施层对业务模块保持零依赖。基础设施层只保留通用 ES 原语（写入/删除/响应解析）。
 */
@Component
public class TaskEsSearchService {

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private EsUtil esUtil;

    /**
     * 任务关键词分页搜索：ES 完成 关键词匹配 + 待接单过滤 + 分类/奖励/时间过滤 + 排序 + 分页，
     * 数据库仅按返回的 ID 回查实体。
     *
     * @param keyword     搜索关键词
     * @param categoryId  任务分类ID，可空
     * @param startReward 最低奖励，可空
     * @param endReward   最高奖励，可空
     * @param startTime   发布时间开始，可空
     * @param endTime     发布时间结束，可空
     * @param pageNum     页码（从1开始）
     * @param pageSize    每页条数
     * @return 分页结果：total 为精确匹配总数，records 为当前页 ID（保持 ES 排序顺序）
     * @throws IOException 异常信息
     */
    public PageResult<Long> searchTaskPage(String keyword, Long categoryId, Integer startReward, Integer endReward,
                                           LocalDateTime startTime, LocalDateTime endTime,
                                           long pageNum, long pageSize) throws IOException {
        int from = (int) ((pageNum - 1) * pageSize);
        int size = (int) pageSize;
        SearchResponse<EsDTO> resp = esClient.search(s -> {
            s.index(PrefixConstants.TASK_INDEX)
                    .from(from)
                    .size(size)
                    .trackTotalHits(th -> th.enabled(true))   // 返回精确 total，供前端分页
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm.fields("title", "description").query(keyword)));
                        // 仅检索待接单任务（与数据库查询条件保持一致）
                        b.filter(f -> f.term(t -> t.field("status").value(TaskStatus.PENDING.getCode())));
                        if (categoryId != null) {
                            b.filter(f -> f.term(t -> t.field("categoryId").value(categoryId)));
                        }
                        if (startReward != null) {
                            b.filter(f -> f.range(r -> r.field("reward").gte(JsonData.of(startReward))));
                        }
                        if (endReward != null) {
                            b.filter(f -> f.range(r -> r.field("reward").lte(JsonData.of(endReward))));
                        }
                        if (startTime != null) {
                            b.filter(f -> f.range(r -> r.field("createTime").gte(JsonData.of(startTime.toString()))));
                        }
                        if (endTime != null) {
                            b.filter(f -> f.range(r -> r.field("createTime").lte(JsonData.of(endTime.toString()))));
                        }
                        return b;
                    }))
                    .sort(so -> so.field(f -> f.field("createTime").order(SortOrder.Desc)));
            return s;
        }, EsDTO.class);
        return esUtil.toPageResult(resp);
    }
}
