package com.rambo.module.user.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "LoginDTO", description = "用户登录请求参数")
public class LoginDTO implements Serializable {
    @Schema(description = "手机号")
    @NotBlank(message = "手机号不能为空")
    private String phone;
    @Schema(description = "验证码")
    @NotBlank(message = "验证码不能为空")
    private String code;
}
