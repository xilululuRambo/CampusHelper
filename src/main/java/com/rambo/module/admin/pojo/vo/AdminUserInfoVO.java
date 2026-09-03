package com.rambo.module.admin.pojo.vo;

import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserInfoVO{

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "学生ID")
    private String studentId;

    @Schema(description = "头像URL")
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

    @Schema(description = "学生认证状态：0-未认证，1-已认证")
    private UserAuthStatus authStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}