package com.rambo.infrastructure.auth;

import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.config.JwtProperties;
import com.rambo.common.context.DeviceHolder;
import com.rambo.common.context.IdHolder;
import com.rambo.common.context.RoleHolder;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.infrastructure.cache.LockClient;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    public static final String AUTHORIZATION = "Authorization";

    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private JwtProperties jwtProperties;
    @Resource
    private LockClient lockClient;

    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        // OPTIONS 预检请求直接放行（无鉴权头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 1. 获取请求头 accessToken
        String accessToken = request.getHeader(AUTHORIZATION);

        // 校验 accessToken 是否为空
        if (accessToken == null || accessToken.isEmpty()) {
            return true;
        }

        // 移除Bearer 前缀
        if (accessToken.startsWith("Bearer ")) {
            accessToken = accessToken.substring(7);

        }


        String id;
        String deviceId;
        try {
            // 2. 一次解析 token，取出所有 claim
            Map<String, Object> claims = jwtUtil.parseToken(accessToken);
            id = (String) claims.get("id");
            String role = (String) claims.get("role");

            // 管理员：建立身份上下文，并校验 accessToken 是否已被登出拉黑
            if (EnumConstants.ROLE_ADMIN.equals(role) || EnumConstants.ROLE_SUPER_ADMIN.equals(role)) {
                // 校验 accessToken 是否在黑名单中（已被主动登出）
                if (cacheClient.hasKey(PrefixConstants.AT_BLACKLIST + accessToken)) {
                    return true;
                }
                // 账号状态校验：被禁用管理员即使 AT 未过期/未进黑名单也立即拒绝。
                // AT 是无状态 JWT，禁用时无法逐个拉黑，靠管理端 updateInfo/disable 写入的禁用标记兜底
                // （与用户侧 USER_DISABLED 语义对齐，避免"禁用只删 RT、AT 仍有效"的漏洞）
                if (cacheClient.hasKey(PrefixConstants.ADMIN_DISABLED + id)) {
                    throw new BusinessException(MessageConstants.ADMIN_DISABLED);
                }
                // 将 accessToken 存入 ThreadLocal，供 logout 等场景使用
                AccessTokenHolder.setToken(accessToken);
                IdHolder.setId(Long.parseLong(id));
                RoleHolder.setRole(role);
                return true;
            }

            // ==================== 普通用户：完整会话管理 ====================
            deviceId = (String) claims.get("deviceId");

            // 校验 accessToken 是否在黑名单中（已被主动踢下线）
            if (cacheClient.hasKey(PrefixConstants.AT_BLACKLIST + accessToken)) {
                return true;
            }

            // 账号状态校验：被禁用用户即使 AT 未过期/未进黑名单也立即拒绝。
            // AT 是无状态 JWT，禁用时无法逐个拉黑，靠管理端 enable() 写入的禁用标记兜底
            if (cacheClient.hasKey(PrefixConstants.USER_DISABLED + id)) {
                throw new BusinessException(MessageConstants.USER_DISABLED);
            }

            // 将 accessToken 存入 ThreadLocal，供 logout 等场景使用
            AccessTokenHolder.setToken(accessToken);

            // 用户活跃时续期该设备 field 的过期时间（RMapCache 原生支持 field 级 TTL）
            String userTokensKey = PrefixConstants.USER_TOKENS + id;
            String currentRt = cacheClient.mapGet(userTokensKey, deviceId);
            if (currentRt != null) {
                cacheClient.mapPut(userTokensKey, deviceId, currentRt, jwtProperties.getRefreshExpiration(), TimeUnit.MILLISECONDS);
            }

            //  将用户ID设置到ThreadLocal中
            IdHolder.setId(Long.parseLong(id));
            DeviceHolder.setDeviceId(Long.parseLong(deviceId));
            // 普通用户统一标记角色（操作日志等场景需区分身份）
            RoleHolder.setRole(EnumConstants.ROLE_USER);

            // 校验通过，放行
            return true;

        } catch (ExpiredJwtException e) {
            // 管理员 token 过期：走简单刷新（String 存储，无黑名单/设备维度）
            if (EnumConstants.ROLE_ADMIN.equals(e.getClaims().get("role"))
                    || EnumConstants.ROLE_SUPER_ADMIN.equals(e.getClaims().get("role"))) {
                String refreshToken = request.getHeader("Refresh-Token");
                // 校验 refreshToken 是否为空
                if (refreshToken == null || refreshToken.isEmpty()) {
                    return true;
                }
                // 从 refreshToken 中获取管理员ID
                try {
                    String adminId = jwtUtil.parseStringClaim(refreshToken);

                    // 加锁，防止并发刷新（与用户锁隔离，避免 admin 表和 user 表 id 冲突）
                    String adminLockKey = PrefixConstants.ADMIN_REFRESH_LOCK + adminId;
                    try {
                        boolean adminLocked = lockClient.tryLock(adminLockKey, NumConstants.LOCK_WAIT_TIME_MILLISECONDS, NumConstants.LOCK_HOLD_TIME_MILLISECONDS, TimeUnit.MILLISECONDS);
                        if (!adminLocked) {
                            // 没抢到锁，说明有其他线程正在刷新，本次直接放行（不带身份）
                            return true;
                        }

                        // 比对 Redis 中的 RT（管理员为 String 结构）
                        String adminTokensKey = PrefixConstants.ADMIN_TOKENS + adminId;
                        String redisRt = cacheClient.get(adminTokensKey);
                        if (redisRt == null || !redisRt.equals(refreshToken)) {
                            // RT 失效或重放：拒绝刷新
                            return true;
                        }

                        // 生成新的双 token，必须保留原管理员角色，否则超级管理员刷新后会会被……降级被当作普通用户解析
                        Map<String, Object> adminClaims = new HashMap<>();
                        adminClaims.put("id", adminId);
                        adminClaims.put("role", e.getClaims().get("role"));
                        String newAdminAccessToken = jwtUtil.createAccessToken(adminClaims);
                        String newAdminRefreshToken = jwtUtil.createRefreshToken(adminClaims);

                        // 更新 Redis 并续期（TTL 单位必须为毫秒）
                        cacheClient.set(adminTokensKey, newAdminRefreshToken,
                                jwtProperties.getRefreshExpiration(), TimeUnit.MILLISECONDS);
                        response.setHeader(AUTHORIZATION, newAdminAccessToken);
                        response.setHeader("Refresh-Token", newAdminRefreshToken);
                        IdHolder.setId(Long.parseLong(adminId));
                        // 角色必须与刷新前一致（超管不能被降级为普通管理员）
                        RoleHolder.setRole((String) adminClaims.get("role"));
                        return true;
                    } finally {
                        // 如果当前线程还持有锁，手动释放（防止超时后的残留）
                        lockClient.unlock(adminLockKey);
                    }
                } catch (Exception ex) {
                    // RT 解析失败（如 RT 也过期）：登录失效，放行后由 AdminInterceptor 拒绝
                    log.error("管理员JWT解析异常", ex);
                    return true;
                }
            }

            // ==================== 普通用户：Refresh Token Rotation ====================
            String refreshToken = request.getHeader("Refresh-Token");
            // 校验 refreshToken 是否为空
            if (refreshToken == null || refreshToken.isEmpty()) {
                return true;
            }

            // 从 refreshToken 中获取用户ID
            try {
                id = jwtUtil.parseStringClaim(refreshToken);
                deviceId = jwtUtil.parseDeviceIdClaim(refreshToken);
            } catch (Exception ex) {
                log.error("JWT解析异常", ex);
                return true;
            }

            // 刷新前校验账号禁用标记：被禁用用户不允许刷新续期（RT Rotation 不得绕过禁用）
            if (cacheClient.hasKey(PrefixConstants.USER_DISABLED + id)) {
                throw new BusinessException(MessageConstants.USER_DISABLED);
            }

            // 3. 加锁，防止并发刷新
            String lockKey = PrefixConstants.REFRESH_LOCK + id;
            try {
                // 尝试加锁，等待 500 毫秒，锁持有 3000 毫秒后自动释放（防止死锁）
                boolean locked = lockClient.tryLock(lockKey, NumConstants.LOCK_WAIT_TIME_MILLISECONDS, NumConstants.LOCK_HOLD_TIME_MILLISECONDS, TimeUnit.MILLISECONDS);
                if (!locked) {
                    // 没抢到锁，说明有其他线程正在刷新，本次直接放行（不带用户信息）
                    return true;
                }

                // 从 Hash 中取出该设备当前有效的 RT
                String userTokensKey = PrefixConstants.USER_TOKENS + id;
                Object redisTokenObj = cacheClient.mapGet(userTokensKey, deviceId);
                if (redisTokenObj == null) {
                    // 设备已被踢下线，拒绝刷新
                    return true;
                }
                if (!redisTokenObj.toString().equals(refreshToken)) {
                    // 旧 RT 重放：判定为疑似盗用，只踢该设备，避免单点泄露导致全设备下线
                    log.warn("Refresh Token 重放，用户：{}，设备：{}，疑似盗用，已踢出该设备", id, deviceId);
                    cacheClient.mapRemove(userTokensKey, deviceId);
                    return true;
                }
                // 生成新的 accessToken + Refresh Token（RT Rotation）
                Map<String, Object> map = new HashMap<>();
                map.put("id", id);
                accessToken = jwtUtil.createAccessToken(map);
                String newRefreshToken = jwtUtil.createRefreshToken(map);

                // 用新 RT 覆盖 Hash 中的旧 RT，并续期该 field 过期（RT Rotation）
                cacheClient.mapPut(userTokensKey, deviceId, newRefreshToken, jwtProperties.getRefreshExpiration(), TimeUnit.MILLISECONDS);
                response.setHeader(AUTHORIZATION, accessToken);
                response.setHeader("Refresh-Token", newRefreshToken);
                IdHolder.setId(Long.parseLong(id));
                DeviceHolder.setDeviceId(Long.parseLong(deviceId));
                // 刷新后同样补全普通用户角色，与首次登录保持一致
                RoleHolder.setRole(EnumConstants.ROLE_USER);
                return true;
            } catch (Exception ex) {
                log.error("刷新 Token 异常", ex);
                return true;
            } finally {
                // 如果当前线程还持有锁，手动释放（防止超时后的残留）
                lockClient.unlock(lockKey);
            }
        } catch (JwtException | IllegalArgumentException e) {
            // token 伪造/损坏/空：不给刷新机会，明确返回 401
            // （ExpiredJwtException 已在上方单独处理，能走到这里说明 token 根本不可信）
            throw new BusinessException(MessageConstants.UNAUTHORIZED);
        }
    }

    // 清除 ThreadLocal
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        IdHolder.clearId();
        DeviceHolder.clearDeviceId();
        AccessTokenHolder.clear();
        RoleHolder.clearRole();
    }
}