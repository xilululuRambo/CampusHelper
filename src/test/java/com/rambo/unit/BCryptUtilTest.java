package com.rambo.unit;

import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.auth.BCryptUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BCrypt 密码工具单元测试（纯 JUnit，无 Spring 上下文）。
 *
 * 覆盖：加密往返、错误密码、空值、非 $2a 前缀、损坏密文兜底、
 * 以及「迁移后的 Spring Security BCrypt 与旧 jbcrypt 哈希（$2a$）兼容」回归。
 */
class BCryptUtilTest {

    private static final String SEED_ADMIN_HASH =
            "$2a$10$ddDD3d0rB2vLB1hwEedw8uaY/p9nrvsFpJk5VGuimalDWK8qYlauC";

    @Test
    @DisplayName("加密：明文可被 check 验证通过（往返一致）")
    void encrypt_roundTrip_success() {
        String hash = BCryptUtil.encrypt("admin123456");
        assertThat(hash).startsWith("$2a$");
        assertThat(BCryptUtil.check("admin123456", hash)).isTrue();
    }

    @Test
    @DisplayName("加密：同一明文两次加密产生不同盐值（不可预测）")
    void encrypt_samePassword_differentSalt() {
        String h1 = BCryptUtil.encrypt("abc123456");
        String h2 = BCryptUtil.encrypt("abc123456");
        assertThat(h1).isNotEqualTo(h2);
        // 但两者都能校验通过
        assertThat(BCryptUtil.check("abc123456", h1)).isTrue();
        assertThat(BCryptUtil.check("abc123456", h2)).isTrue();
    }

    @Test
    @DisplayName("加密：空字符串被拒")
    void encrypt_emptyPassword_failed() {
        assertThatThrownBy(() -> BCryptUtil.encrypt(""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("加密：null 被拒")
    void encrypt_nullPassword_failed() {
        assertThatThrownBy(() -> BCryptUtil.encrypt(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("校验：错误密码返回 false")
    void check_wrongPassword_false() {
        String hash = BCryptUtil.encrypt("admin123456");
        assertThat(BCryptUtil.check("wrong-password", hash)).isFalse();
    }

    @Test
    @DisplayName("校验：null 密码 / null 哈希返回 false（不抛异常）")
    void check_nullInputs_false() {
        String hash = BCryptUtil.encrypt("admin123456");
        assertThat(BCryptUtil.check(null, hash)).isFalse();
        assertThat(BCryptUtil.check("admin123456", null)).isFalse();
        assertThat(BCryptUtil.check(null, null)).isFalse();
    }

    @Test
    @DisplayName("校验：非 $2a 前缀的非法密文返回 false")
    void check_nonBcryptPrefix_false() {
        assertThat(BCryptUtil.check("admin123456", "plain-text-hash")).isFalse();
        assertThat(BCryptUtil.check("admin123456", "$2y$abc")).isFalse();
    }

    @Test
    @DisplayName("校验：损坏密文（截断/垃圾字符）返回 false 而非抛异常")
    void check_corruptedHash_false() {
        String hash = BCryptUtil.encrypt("admin123456");
        assertThat(BCryptUtil.check("admin123456", hash + "corrupted")).isFalse();
        assertThat(BCryptUtil.check("admin123456", "$2a$10$!!invalid!!")).isFalse();
    }

    @Test
    @DisplayName("回归：seed 数据管理员哈希（jbcrypt 时代生成）仍可被 Spring Security BCrypt 校验")
    void check_seedAdminHash_compatible() {
        // t_admin 种子密码 admin123456 的 $2a$ 哈希（jbcrypt 0.4 生成）
        assertThat(BCryptUtil.check("admin123456", SEED_ADMIN_HASH)).isTrue();
        // 错误密码校验该哈希必须失败
        assertThat(BCryptUtil.check("admin12345", SEED_ADMIN_HASH)).isFalse();
    }
}
