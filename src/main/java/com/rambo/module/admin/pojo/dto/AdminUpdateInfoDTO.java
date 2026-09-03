package com.rambo.module.admin.pojo.dto;

import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class AdminUpdateInfoDTO {
    @Schema(description = "管理员姓名")
    @NotBlank(message = "姓名不能为空")
    private String name;

    @Schema(description = "管理员手机号")
    @NotBlank(message = "手机号不能为空")
    private String phone;

    @Schema(description = "管理员状态")
    @NotNull(message = "状态不能为空")
    private AdminStatus status;

    @Schema(description = "管理员角色（为空表示不调整）")
    private AdminRole role;
}
