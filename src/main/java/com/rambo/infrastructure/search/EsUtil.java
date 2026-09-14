package com.rambo.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.result.PageResult;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class EsUtil {

    @Resource
    private ElasticsearchClient esClient;


    /**
     * 新增/更新ES文档（新增和更新共用，id存在就覆盖）。
     * <p>
     * 防乱序：使用调用方显式传入的 {@code version}（发件箱行 id）作为 ES 外部版本号。
     * 同一文档并发写入时，ES 只接受版本号更大的请求，旧数据后到会触发 409 版本冲突被拒绝，
     * 避免"旧数据覆盖新数据"的双写乱序问题。
     *
     * @param esDTO   文档DTO
     * @param index   索引名
     * @param version ES 外部版本号（与发件箱行 id 一致）
     * @throws IOException 异常信息
     */
    public void saveOrUpdate(EsDTO esDTO, String index, long version) throws IOException {
        esClient.index(c -> c
                .index(index)
                .id(esDTO.getId().toString())
                .document(esDTO)
                .version(version)
                .versionType(VersionType.External)
        );
    }

    /**
     * 根据id删除ES文档（不带版本，仅用于运维/测试场景下手动清理；生产链路请走带版本重载）。
     *
     * @param id 文档ID
     * @throws IOException 异常信息
     */
    public void deleteById(Long id, String index) throws IOException {
        esClient.delete(d -> d
                .index(index)
                .id(id.toString())
        );
    }

    /**
     * 根据id删除ES文档（带外部版本号）。
     * <p>删除同样使用 external version：版本号更大的删除会留下「墓碑」版本，
     * 使随后到达的、版本更小的旧写入被 ES 以 409 拒绝，避免「已删除的商品被旧事件复活」。</p>
     *
     * @param id      文档ID
     * @param index   索引名
     * @param version 外部版本号（与发件箱行 id 一致）
     * @throws IOException 异常信息
     */
    public void deleteById(Long id, String index, Long version) throws IOException {
        esClient.delete(d -> {
            d.index(index).id(id.toString());
            if (version != null) {
                d.version(version).versionType(VersionType.External);
            }
            return d;
        });
    }

    /**
     * 将 ES 响应转换为分页结果（精确 total + 当前页 ID 列表，保持 ES 排序顺序）。
     * 通用基础设施原语：业务模块构造搜索请求后复用本方法完成响应解析。
     */
    public PageResult<Long> toPageResult(SearchResponse<EsDTO> resp) {
        long total = resp.hits().total() != null ? resp.hits().total().value() : 0;
        List<Long> idList = new ArrayList<>();
        for (Hit<EsDTO> hit : resp.hits().hits()) {
            if (hit.source() != null) {
                idList.add(hit.source().getId());
            }
        }
        return new PageResult<>(total, idList);
    }

    /**
     * 商品同步——写入 ES 时使用 {@code version} 作为 external version，
     * 由 {@link com.rambo.module.goods.server.service.impl.GoodsEsSyncService} 传入发件箱行 id。
     */
    public void saveGoods(EsDTO esDTO, long version) throws IOException {
        saveOrUpdate(esDTO, PrefixConstants.GOODS_INDEX, version);
    }

    /**
     * 任务同步——写入 ES 时使用 {@code version} 作为 external version，
     * 由 {@link com.rambo.module.task.server.service.impl.TaskEsSyncService} 传入发件箱行 id。
     */
    public void saveTask(EsDTO esDTO, long version) throws IOException {
        saveOrUpdate(esDTO, PrefixConstants.TASK_INDEX, version);
    }

    /**
     * 商品删除（不带版本，仅用于运维/测试场景下手动清理；生产链路请走带版本重载）。
     */
    public void deleteGoods(Long id) throws IOException {
        deleteById(id, PrefixConstants.GOODS_INDEX);
    }

    /**
     * 商品删除（带外部版本号，由发件箱行 id 担任）。
     */
    public void deleteGoods(Long id, Long version) throws IOException {
        deleteById(id, PrefixConstants.GOODS_INDEX, version);
    }

    /**
     * 任务删除（不带版本，仅用于运维/测试场景下手动清理；生产链路请走带版本重载）。
     */
    public void deleteTask(Long id) throws IOException {
        deleteById(id, PrefixConstants.TASK_INDEX);
    }

    /**
     * 任务删除（带外部版本号，由发件箱行 id 担任）。
     */
    public void deleteTask(Long id, Long version) throws IOException {
        deleteById(id, PrefixConstants.TASK_INDEX, version);
    }
}
