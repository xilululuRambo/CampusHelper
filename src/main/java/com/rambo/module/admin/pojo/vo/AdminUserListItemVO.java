package com.rambo.module.admin.pojo.vo;

import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户列表分页VO（精简字段，仅列表页展示所需；完整信息见 AdminUserInfoVO）
 */
@Data
@Schema(description = "用户列表分页VO")
public class AdminUserListItemVO implements Serializable {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "学生ID")
    private String studentId;

    @Schema(description = "头像 OSS 对象名")
    private String avatar;

    @Schema(description = "账号状态：0-正常，1-禁用")
    private UserStatus status;

    @Schema(description = "学生认证状态：0-未认证，1-已认证")
    private UserAuthStatus authStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
