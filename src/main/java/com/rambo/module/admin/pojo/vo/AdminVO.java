package com.rambo.module.admin.pojo.vo;

import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
@Data
public class AdminVO {
    @Schema(description = "管理员ID")
    private Long id;

    @Schema(description = "管理员账号")
    private String account;

    @Schema(description = "管理员姓名")
    private String name;

    @Schema(description = "管理员手机号")
    private String phone;

    @Schema(description = "管理员状态")
    private AdminStatus status;

    @Schema(description = "管理员角色")
    private AdminRole role;
}
