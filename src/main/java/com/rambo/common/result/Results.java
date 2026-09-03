package com.rambo.common.result;


import com.rambo.common.constants.CodeConstants;
import com.rambo.common.constants.MessageConstants;
import lombok.Getter;

@Getter
public enum Results {
    SUCCESS(CodeConstants.SUCCESS, MessageConstants.SUCCESS),
    FAIL(CodeConstants.FAIL, MessageConstants.FAIL);

    private final Integer code;
    private final String msg;

    Results(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
