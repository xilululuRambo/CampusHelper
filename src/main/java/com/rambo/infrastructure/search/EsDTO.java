package com.rambo.infrastructure.search;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    /**
     * 数据版本号（epoch 毫秒时间戳，在业务调用线程取值）。
     * <p>作为 ES 外部版本号（external version）防乱序：同一实体并发同步时，
     * 旧版本数据写入会被 ES 以版本冲突拒绝，避免"旧数据后到覆盖新数据"。</p>
     */
    @Schema(description = "数据版本号（epoch毫秒，防乱序覆盖）", hidden = true)
    private Long updateTime;
}