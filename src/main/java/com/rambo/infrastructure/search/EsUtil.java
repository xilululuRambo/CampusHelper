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
     * 新增/更新ES文档（新增和更新共用，id存在就覆盖）
     * <p>
     * 防乱序：使用 {@code EsDTO.updateTime}（业务线程取值的 epoch 毫秒）作为 ES 外部版本号。
     * 同一文档并发写入时，ES 只接受版本号更大的请求，旧数据后到会触发 409 版本冲突被拒绝，
     * 避免"旧数据覆盖新数据"的双写乱序问题。
     *
     * @param esDTO 文档DTO
     * @throws IOException 异常信息
     */
    public void saveOrUpdate(EsDTO esDTO, String index) throws IOException {
        // updateTime 为 null（历史调用方未设置）时回退 0：首次创建正常，后续任何带版本号的更新都会覆盖它
        long version = esDTO.getUpdateTime() != null ? esDTO.getUpdateTime() : 0L;
        esClient.index(c -> c
                .index(index)
                .id(esDTO.getId().toString())
                .document(esDTO)
                .version(version)
                .versionType(VersionType.External)
        );
    }

    /**
     * 根据id删除ES文档
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

    // 商品同步（直接用）
    public void saveGoods(EsDTO esDTO) throws IOException {
        saveOrUpdate(esDTO, PrefixConstants.GOODS_INDEX);
    }

    // 任务同步（直接用）
    public void saveTask(EsDTO esDTO) throws IOException {
        saveOrUpdate(esDTO, PrefixConstants.TASK_INDEX);
    }

    // 商品删除
    public void deleteGoods(Long id) throws IOException {
        deleteById(id, PrefixConstants.GOODS_INDEX);
    }

    // 任务删除
    public void deleteTask(Long id) throws IOException {
        deleteById(id, PrefixConstants.TASK_INDEX);
    }
}
