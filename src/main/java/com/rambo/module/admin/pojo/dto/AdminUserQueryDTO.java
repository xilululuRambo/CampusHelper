package com.rambo.module.admin.pojo.dto;

import com.rambo.common.result.PageQuery;
import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用户分页查询DTO")
public class AdminUserQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "手机号（精确匹配）")
    private String phone;

    @Schema(description = "用户名（模糊匹配）")
    private String username;

    @Schema(description = "真实姓名（模糊匹配）")
    private String realName;

    @Schema(description = "学号（精确匹配）")
    private String studentId;

    @Schema(description = "账号状态：0-正常，1-禁用")
    private UserStatus status;

    @Schema(description = "学生认证状态：0-未认证，1-已认证")
    private UserAuthStatus authStatus;

    @Schema(description = "注册时间开始")
    private LocalDateTime startTime;

    @Schema(description = "注册时间结束")
    private LocalDateTime endTime;
}
