package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_hot_keywords")
public class HotKeywords implements Serializable {
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "关键词")
    private String keyword;

    @Schema(description = "搜索次数")
    private Integer searchCount;

    @Schema(description = "记录日期")
    private LocalDate recordDate;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
