package com.rambo.module.admin.pojo.dto;

import com.rambo.common.result.PageQuery;
import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "管理员分页查询DTO")
public class AdminQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "账号（精确匹配）")
    private String account;

    @Schema(description = "姓名（模糊匹配）")
    private String name;

    @Schema(description = "状态")
    private AdminStatus status;

    @Schema(description = "角色")
    private AdminRole role;
}
