package com.rambo.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import com.rambo.common.constants.PrefixConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ES 索引统一初始化器：应用启动时确保 goods_index / task_index 存在且映射完整。
 *
 * <p>背景：搜索已下推 ES 做过滤/排序/分页（status/price/categoryId/reward/createTime），
 * 若依赖 ES 动态映射自动建索引，字段类型推断不可控（如 status 可能被映射为 text，
 * 导致 term 过滤失效、range 排序报错），因此启动时统一创建索引并声明映射。</p>
 *
 * <p>幂等：索引已存在则跳过；ES 不可用时捕获异常仅记日志，不阻塞应用启动
 * （业务侧已有 ES 故障降级逻辑）。</p>
 */
@Slf4j
@Component
public class EsIndexInitializer implements ApplicationRunner {

    @Resource
    private ElasticsearchClient esClient;

    /**
     * 是否启用索引自动初始化（测试环境可关闭，避免依赖外部 ES）
     */
    @Value("${es.index-init-enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("ES 索引自动初始化已关闭（es.index-init-enabled=false）");
            return;
        }
        try {
            ensureIndex(PrefixConstants.GOODS_INDEX);
            ensureIndex(PrefixConstants.TASK_INDEX);
            log.info("ES 索引初始化完成");
        } catch (Exception e) {
            // ES 不可用时不阻塞启动，业务侧有降级；也便于排查：失败可看到原因
            log.error("ES 索引初始化失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 确保索引存在：不存在则创建并声明完整映射
     */
    private void ensureIndex(String index) throws Exception {
        boolean exists = esClient.indices().exists(e -> e.index(index)).value();
        if (exists) {
            log.info("ES 索引已存在，跳过创建：{}", index);
            return;
        }
        esClient.indices().create(c -> c
                .index(index)
                .mappings(m -> m.properties(buildMappings())));
        log.info("ES 索引创建成功：{}", index);
    }

    /**
     * 构建统一映射（与 EsDTO 字段一一对应，类型与搜索/过滤/排序语义匹配）：
     * - title/description：text，供 multiMatch 关键词分词搜索
     * - status/categoryId/reward/price：数值类型，供 term 精确过滤与 range 范围过滤
     * - createTime：date，供时间范围过滤与排序
     */
    private Map<String, Property> buildMappings() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("id", Property.of(p -> p.long_(l -> l)));
        properties.put("title", Property.of(p -> p.text(t -> t)));
        properties.put("description", Property.of(p -> p.text(t -> t)));
        properties.put("status", Property.of(p -> p.integer(i -> i)));
        properties.put("price", Property.of(p -> p.long_(l -> l)));
        properties.put("categoryId", Property.of(p -> p.long_(l -> l)));
        properties.put("reward", Property.of(p -> p.integer(i -> i)));
        properties.put("createTime", Property.of(p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis"))));
        return properties;
    }
}
