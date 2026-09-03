package com.rambo.module.admin.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class AdminLoginDTO {
    @Schema(description = "管理员账号")
    @NotBlank(message = "账号不能为空")
    private String account;

    @Schema(description = "管理员密码")
    @NotBlank(message = "密码不能为空")
    private String password;
}
