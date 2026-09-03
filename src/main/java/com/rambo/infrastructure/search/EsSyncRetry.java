package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.common.enumType.RetryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_es_sync_retry")
public class EsSyncRetry {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "业务数据ID 商品ID/任务ID")
    private Long dataId;

    @Schema(description = "数据类型 goods商品 task任务")
    private String dataType;

    @Schema(description = "OSS文件路径，多个用逗号分隔")
    private String fileUrls;

    @Schema(description = "已重试次数")
    private Integer retryCount;

    @Schema(description = "状态 0-待重试 1-重试成功 2-重试失败终止")
    private RetryStatus status;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}