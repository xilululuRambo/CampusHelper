package com.rambo.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ES 专用 ObjectMapper 单元测试（纯单测，不加载 Spring 上下文、不依赖 ES 可用）。
 *
 * <p><b>背景</b>：{@code EsConfig} 原先使用裸 {@code JacksonJsonpMapper()} —— 其内部是未注册任何
 * module 的 ObjectMapper，序列化 {@link EsDTO#getCreateTime()}（LocalDateTime）时直接抛
 * {@code InvalidDefinitionException}，失败发生在发出 HTTP 之前，因此与 ES 是否可用无关，
 * 导致 ES 同步/降级/搜索在任何环境都跑不通。</p>
 *
 * <p><b>为什么不能直接复用 Spring 容器里的 mapper</b>：{@code JacksonConfig} 为 Web 层注册了
 * Long→String（防雪花 ID 在 JS 端精度丢失）与 {@code yyyy-MM-dd HH:mm:ss} 的 LocalDateTime 格式。
 * 而 {@link EsIndexInitializer} 声明的映射是 {@code long} + {@code strict_date_optional_time||epoch_millis}。
 * 已用真实 ES 8.11 验证：写入 {@code "2026-09-13 23:29:00"} 会被 ES 以
 * {@code document_parsing_exception} 400 拒绝 —— 也就是说复用 Web mapper 只是把失败从
 * 「客户端序列化」挪到「ES 端 mapping 解析」，并没有修好。</p>
 *
 * <p>本测试把这两个约定钉死：时间为 ISO-8601、数值保持数字类型。</p>
 */
class EsConfigObjectMapperTest {

    private final ObjectMapper mapper = EsConfig.esObjectMapper();

    private EsDTO sampleDto() {
        EsDTO dto = new EsDTO();
        dto.setId(1L);
        dto.setTitle("二手教材");
        dto.setCreateTime(LocalDateTime.of(2026, 9, 13, 23, 29, 0));
        return dto;
    }

    @Test
    @DisplayName("createTime 序列化为 ISO-8601，可被 ISO_LOCAL_DATE_TIME 解析")
    void createTime_shouldSerializeAsIso8601() throws Exception {
        String json = mapper.writeValueAsString(sampleDto());
        String createTime = mapper.readTree(json).get("createTime").asText();

        // 必须是 'T' 分隔的 ISO-8601 —— 这正是索引声明的 strict_date_optional_time 接受的形态
        assertThat(createTime).startsWith("2026-09-13T23:29");
        assertThat(createTime).doesNotContain(" ");
        assertThat(LocalDateTime.parse(createTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .isEqualTo(LocalDateTime.of(2026, 9, 13, 23, 29, 0));
    }

    @Test
    @DisplayName("不得退化为数组：WRITE_DATES_AS_TIMESTAMPS 必须关闭")
    void createTime_shouldNotBecomeArray() throws Exception {
        String json = mapper.writeValueAsString(sampleDto());

        // JavaTimeModule 的默认行为是 [2026,9,13,23,29,0]，ES 同样无法解析
        assertThat(json).doesNotContain("[2026");
        assertThat(json).doesNotContain("\"createTime\":[");
        assertThat(mapper.readTree(json).get("createTime").isTextual()).isTrue();
    }

    @Test
    @DisplayName("数值字段保持数字类型，不被 Web 层的 Long→String 污染")
    void numericFields_shouldStayNumeric() throws Exception {
        EsDTO dto = new EsDTO();
        dto.setId(1234567890123456789L);
        dto.setPrice(1999L);
        dto.setCategoryId(7L);
        dto.setStatus(1);
        dto.setReward(10);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));

        // 索引声明 id/price/categoryId 为 long，status/reward 为 integer
        assertThat(node.get("id").isNumber()).isTrue();
        assertThat(node.get("id").asLong()).isEqualTo(1234567890123456789L);
        assertThat(node.get("price").isNumber()).isTrue();
        assertThat(node.get("categoryId").isNumber()).isTrue();
        assertThat(node.get("status").isNumber()).isTrue();
        assertThat(node.get("reward").isNumber()).isTrue();
    }

    @Test
    @DisplayName("两条链路的时间格式互斥：Web 层格式无法被 ISO 解析（证明不可混用）")
    void webTimeFormat_shouldBeRejectedByIsoParser() {
        // Web 层的 yyyy-MM-dd HH:mm:ss —— 真实 ES 已证实会被 document_parsing_exception 拒绝
        assertThatThrownBy(() -> LocalDateTime.parse("2026-09-13 23:29:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .as("若此行不抛异常，说明两套格式判断有误")
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    @DisplayName("round-trip：序列化结果可被同一 mapper 反序列化（搜索结果解析依赖）")
    void roundTrip() throws Exception {
        EsDTO dto = sampleDto();

        EsDTO back = mapper.readValue(mapper.writeValueAsString(dto), EsDTO.class);

        assertThat(back.getId()).isEqualTo(1L);
        assertThat(back.getTitle()).isEqualTo("二手教材");
        assertThat(back.getCreateTime()).isEqualTo(dto.getCreateTime());
    }
}
