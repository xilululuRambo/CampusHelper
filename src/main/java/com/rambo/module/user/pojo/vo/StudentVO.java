package com.rambo.module.user.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "学生信息VO")
public class StudentVO {

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "专业")
    private String major;

    @Schema(description = "年级")
    private String grade;

    @Schema(description = "学院")
    private String college;
}