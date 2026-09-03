package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.common.enumType.CategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_task_category")
public class TaskCategory implements Serializable {

    @Schema(description = "任务分类ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务分类名称")
    private String name;

    @Schema(description = "任务分类描述")
    private String description;

    @Schema(description = "任务分类状态：0-正常，1-禁用")
    private CategoryStatus status;

    @Schema(description = "任务分类创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "任务分类更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}