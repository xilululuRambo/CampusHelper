package com.rambo.infrastructure.search;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ES 文档 DTO（基础设施层抽象）。
 *
 * <p>作为 ES 索引文档的统一载体，由 {@code GoodsMapper}/{@code TaskMapper} 回源后
 * 经 {@code BeanUtil.copyProperties} 装入。</p>
 *
 * <p><b>字段只放「要写进 ES 文档的内容」</b>——ES external version 不再由本类承载：
 * 历史上曾有 {@code updateTime}（{@code Long}，epoch 毫秒）字段兼作版本号载体，
 * 现已移除，版本改由调用方通过 {@link EsUtil#saveOrUpdate} 的 {@code version} 参数显式传入
 * （值取自发件箱行 id）。移除它还有第二个理由：实体里的 {@code updateTime} 是
 * {@link LocalDateTime}（如 {@code Task.updateTime}），{@code BeanUtil} 拷贝进来后会被
 * ES 按既有 {@code long} 映射拒绝（{@code document_parsing_exception}）。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EsDTO {
    @Schema(description = "ID")
    @NotNull(message = "ID不能为空")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态码（商品/任务枚举 code），用于 ES 过滤")
    private Integer status;

    @Schema(description = "商品价格（分）")
    private Long price;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "任务奖励（积分）")
    private Integer reward;

    @Schema(description = "创建时间，用于 ES 排序")
    private LocalDateTime createTime;
}
