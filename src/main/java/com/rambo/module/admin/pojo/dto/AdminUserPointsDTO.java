package com.rambo.module.admin.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员调整用户积分DTO
 */
@Data
@Schema(description = "管理员调整用户积分DTO")
public class AdminUserPointsDTO implements Serializable {

    @Schema(description = "积分变动值（正数增加、负数扣减，扣减后积分不得为负）")
    @NotNull(message = "积分变动值不能为空")
    private Integer delta;

    @Schema(description = "调整原因（审计留痕）")
    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
