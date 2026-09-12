package com.rambo.module.user.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_user")
public class User implements Serializable {

    @Schema(description = "用户ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "学生ID")
    private String studentId;

    @Schema(description = "头像 OSS 对象名")
    private String avatar;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "积分")
    private Integer points;

    @Schema(description = "信誉分")
    private Integer creditScore;

    @Schema(description = "余额")
    private Long balance;

    @Schema(description = "账号状态：0-正常，1-禁用")
    private UserStatus status;

    @Schema(description = "认证状态：0-未认证，1-已认证")
    private UserAuthStatus authStatus;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Version
    @Schema(description = "乐观锁版本号")
    private Integer version;
}