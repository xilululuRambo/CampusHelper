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
@Schema(name = "UserAuthDTO", description = "用户认证参数")
public class UserAuthDTO implements Serializable {

    @NotBlank(message = "学号不能为空")
    @Schema(description = "学号")
    private String studentId;

    @NotBlank(message = "真实姓名不能为空")
    @Schema(description = "真实姓名")
    private String realName;
}
