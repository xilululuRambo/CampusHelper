package com.rambo.module.admin.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_admin")
public class Admin implements Serializable {
    @Schema(description = "管理员ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "管理员账号")
    private String account;

    @Schema(description = "管理员密码")
    private String password;

    @Schema(description = "管理员姓名")
    private String name;

    @Schema(description = "管理员手机号")
    private String phone;

    @Schema(description = "管理员状态")
    private AdminStatus status;

    @Schema(description = "管理员角色")
    private AdminRole role;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
