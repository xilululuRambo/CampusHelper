package com.rambo.module.admin.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员修改用户基本信息DTO
 * 仅支持修改：用户名/手机号/真实姓名；
 * 积分、信誉分、余额、状态等字段禁止通过此接口修改（必须走独立接口，便于审计留痕）
 */
@Data
@Schema(description = "管理员修改用户基本信息DTO")
public class AdminUserUpdateDTO implements Serializable {

    @Schema(description = "用户名（可选，需唯一且格式合法）")
    private String username;

    @Schema(description = "手机号（可选，需唯一且格式合法）")
    private String phone;

    @Schema(description = "真实姓名（可选，2-4位中文）")
    private String realName;
}
