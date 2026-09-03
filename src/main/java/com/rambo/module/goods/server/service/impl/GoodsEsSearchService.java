package com.rambo.module.goods.server.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.enumType.SortDirectionEnum;
import com.rambo.common.result.PageResult;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.goods.enums.GoodsStatus;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 商品 ES 搜索组件（业务层）：关键词分页搜索属于商品业务规则
 * （仅检索在售、价格区间过滤、排序策略），从基础设施层 {@code EsUtil} 下沉到业务模块，
 * 使基础设施层对业务模块保持零依赖。基础设施层只保留通用 ES 原语（写入/删除/响应解析）。
 */
@Component
public class GoodsEsSearchService {

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private EsUtil esUtil;

    /**
     * 商品关键词分页搜索：ES 完成 关键词匹配 + 在售过滤 + 价格过滤 + 排序 + 分页，
     * 数据库仅按返回的 ID 回查实体（避免默认 size=10 截断导致跨页数据缺失）。
     *
     * @param keyword       搜索关键词
     * @param minPrice      最低价格（分），可空
     * @param maxPrice      最高价格（分），可空
     * @param sortDirection 排序方向（ASC=价格升序，DESC=价格降序，null=创建时间降序）
     * @param pageNum       页码（从1开始）
     * @param pageSize      每页条数
     * @return 分页结果：total 为精确匹配总数，records 为当前页 ID（保持 ES 排序顺序）
     * @throws IOException 异常信息
     */
    public PageResult<Long> searchGoodsPage(String keyword, Long minPrice, Long maxPrice,
                                            SortDirectionEnum sortDirection, long pageNum, long pageSize) throws IOException {
        int from = (int) ((pageNum - 1) * pageSize);
        int size = (int) pageSize;
        SearchResponse<EsDTO> resp = esClient.search(s -> {
            s.index(PrefixConstants.GOODS_INDEX)
                    .from(from)
                    .size(size)
                    .trackTotalHits(th -> th.enabled(true))   // 返回精确 total，供前端分页
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm.fields("title", "description").query(keyword)));
                        // 仅检索在售商品（与数据库查询条件保持一致）
                        b.filter(f -> f.term(t -> t.field("status").value(GoodsStatus.NORMAL.getCode())));
                        if (minPrice != null) {
                            b.filter(f -> f.range(r -> r.field("price").gte(JsonData.of(minPrice))));
                        }
                        if (maxPrice != null) {
                            b.filter(f -> f.range(r -> r.field("price").lte(JsonData.of(maxPrice))));
                        }
                        return b;
                    }));
            // 排序：指定方向按价格，否则按创建时间降序（与数据库逻辑一致）
            if (sortDirection == SortDirectionEnum.ASC) {
                s.sort(so -> so.field(f -> f.field("price").order(SortOrder.Asc)));
            } else if (sortDirection == SortDirectionEnum.DESC) {
                s.sort(so -> so.field(f -> f.field("price").order(SortOrder.Desc)));
            } else {
                s.sort(so -> so.field(f -> f.field("createTime").order(SortOrder.Desc)));
            }
            return s;
        }, EsDTO.class);
        return esUtil.toPageResult(resp);
    }
}
