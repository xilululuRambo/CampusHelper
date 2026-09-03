package com.rambo.infrastructure.auth;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import org.springframework.security.crypto.bcrypt.BCrypt;

public class BCryptUtil {
    /**
     * 加密
     */
    public static String encrypt(String password){

        if (password == null || password.isEmpty()) {
            throw new BusinessException(MessageConstants.ADMIN_PASSWORD_EMPTY);
        }

        return BCrypt.hashpw(password,BCrypt.gensalt());
    }

    /**
     * 对比
     */
    public static boolean check(String password,String hashPassword){
        //判空
        if (password == null || hashPassword == null) {
            return false;
        }

        // 防止空指针或非法密文格式
        if (!hashPassword.startsWith("$2a")) {
            return false;
        }

        // Spring Security BCrypt 对损坏密文抛 IllegalArgumentException，登录场景兜底为校验失败而非 500
        try {
            return BCrypt.checkpw(password, hashPassword);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
