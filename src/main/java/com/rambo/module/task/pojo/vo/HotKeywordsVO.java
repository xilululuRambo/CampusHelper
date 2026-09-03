package com.rambo.module.task.pojo.vo;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_hot_keywords")
public class HotKeywordsVO implements Serializable {
    @Schema(description = "关键词")
    private String keyword;

    @Schema(description = "搜索次数")
    private Integer searchCount;
}
