package com.rambo.common.enumType;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * ES 同步操作类型：区分发件箱中的一次同步意图是「写入/更新」还是「删除」。
 */
@Getter
public enum EsSyncOp {

    // 0-新增/更新（UPSERT） 1-删除（DELETE）
    UPSERT(0, "新增/更新"),
    DELETE(1, "删除");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    EsSyncOp(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
