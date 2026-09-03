package com.rambo.module.user.pojo.dto;


import com.rambo.module.user.enums.AddressStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "AddressDTO", description = "用户地址请求参数")
public class AddressDTO implements Serializable {
    @Schema(description = "收货人姓名")
    @NotBlank(message = "收货人姓名不能为空")
    private String receiverName;

    @Schema(description = "收货人手机号")
    @NotBlank(message = "收货人手机号不能为空")
    private String receiverPhone;

    @Schema(description = "省")
    @NotBlank(message = "省不能为空")
    private String province;

    @Schema(description = "市")
    @NotBlank(message = "市不能为空")
    private String city;

    @Schema(description = "区/县")
    @NotBlank(message = "区/县不能为空")
    private String district;

    @Schema(description = "详细地址（街道、门牌号等）")
    @NotBlank(message = "详细地址不能为空")
    private String detailAddress;

    @Schema(description = "是否默认地址：0-否，1-是")
    private AddressStatus isDefault;
}