package com.rambo.module.task.pojo.vo;

import com.rambo.common.annotation.OssUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskVO implements Serializable {

    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "发布者用户ID")
    private Long publisherId;

    @Schema(description = "发布者用户名")
    private String username;

    @Schema(description = "发布者头像(OSS对象名)")
    @OssUrl
    private String publisherAvatar;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "奖励（积分）")
    private Integer reward;

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "任务地址ID")
    private Long addressId;

    @Schema(description = "任务状态：0-待接单 1-进行中 2-待确认 3-已完成 4-已取消")
    private Integer status;

    @Schema(description = "接单者用户ID")
    private Long applicantId;

    @Schema(description = "任务地址")
    private String address;

    @Schema(description = "截止时间")
    private LocalDateTime deadline;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
