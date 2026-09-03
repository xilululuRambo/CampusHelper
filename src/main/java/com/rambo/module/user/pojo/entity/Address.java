package com.rambo.module.user.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.module.user.enums.AddressStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_address")
public class Address implements Serializable {

    @Schema(description = "地址ID，主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID，关联用户表")
    private Long userId;

    @Schema(description = "收货人姓名")
    private String receiverName;

    @Schema(description = "收货人手机号")
    private String receiverPhone;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区/县")
    private String district;

    @Schema(description = "详细地址（街道、门牌号等）")
    private String detailAddress;

    @Schema(description = "是否默认地址：0-否，1-是")
    private AddressStatus isDefault;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}