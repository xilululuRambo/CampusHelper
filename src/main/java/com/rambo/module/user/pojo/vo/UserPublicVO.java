package com.rambo.module.user.pojo.vo;

import com.rambo.common.annotation.OssUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "UserInfoVO", description = "用户信息VO")
public class UserPublicVO implements Serializable {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "头像URL")
    @OssUrl
    private String avatar;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "信誉分")
    private Integer creditScore;
}
