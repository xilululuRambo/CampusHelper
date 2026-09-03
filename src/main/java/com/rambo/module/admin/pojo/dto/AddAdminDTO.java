package com.rambo.module.admin.pojo.dto;

import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddAdminDTO {

    @Schema(description = "管理员账号")
    @NotBlank(message = "管理员账号不能为空")
    private String account;

    @Schema(description = "管理员密码")
    @NotBlank(message = "管理员密码不能为空")
    private String password;

    @Schema(description = "管理员姓名")
    private String name;

    @Schema(description = "管理员手机号")
    private String phone;

    @Schema(description = "管理员状态（默认正常）")
    private AdminStatus status;

    @Schema(description = "管理员角色（默认普通管理员）")
    private AdminRole role;
}
