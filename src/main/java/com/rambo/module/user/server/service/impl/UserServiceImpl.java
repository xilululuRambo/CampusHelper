package com.rambo.module.user.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.constants.*;
import com.rambo.infrastructure.auth.AccessTokenHolder;
import com.rambo.common.context.DeviceHolder;
import com.rambo.infrastructure.config.JwtProperties;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.database.TransactionUtils;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.infrastructure.auth.LoginFailCounter;
import com.rambo.common.utils.RegexUtil;
import com.rambo.module.user.pojo.dto.LoginDTO;
import com.rambo.module.user.pojo.dto.UserAuthDTO;
import com.rambo.module.user.pojo.vo.UserPrivateVO;
import com.rambo.module.user.pojo.vo.UserPublicVO;
import com.rambo.module.user.pojo.entity.Student;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.mapper.UserMapper;
import com.rambo.module.user.server.service.StudentService;
import com.rambo.module.user.server.service.UserService;
import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private CacheClient cacheClient;
    @Resource
    private LoginFailCounter loginFailCounter;
    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private StudentService studentService;
    @Resource
    private AliyunOssUtil aliyunOssUtil;
    @Resource
    private JwtProperties jwtProperties;

    /**
     * 发送验证码
     *
     * @param phone 手机号
     */
    @Override
    public void sendCode(String phone) {
        // 校验手机号是否符合格式
        if (!phone.matches(RegexUtil.REGEX_PHONE)) {
            throw new BusinessException(MessageConstants.PHONE_ERROR);
        }

        // 校验验证码冷却时间是否过期
        // 运用包装类，避免redis返回null，避免空指针异常
        Boolean success = cacheClient.setIfAbsent(PrefixConstants.CODE_COOLDOWN_TIME_PREFIX + phone, "1",
                        NumConstants.CODE_COOLDOWN_TIME_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(success)) {
            throw new BusinessException(MessageConstants.CODE_COOLDOWN_ERROR);
        } else {
            // 生成验证码
            String code = RandomUtil.randomNumbers(NumConstants.CODE_LENGTH);

            // 存储验证码到Redis
            cacheClient.set(PrefixConstants.CODE_PREFIX + phone, code, NumConstants.CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
            // 新验证码签发即重置失败计数：每枚验证码独立享有固定次数的尝试额度
            loginFailCounter.clear(PrefixConstants.USER_LOGIN_FAIL + phone);

            // 发送验证码到手机号（验证码属凭证，不落日志，防止日志泄露后任意账号可登录）
            log.info("模拟发送验证码到手机号：{}", phone);
        }
    }

    /**
     * 用户登录与注册
     *
     * @param loginDTO 登录请求参数
     */
    @Override
    public Map<String, Object> login(LoginDTO loginDTO) {
        // 校验手机号是否符合格式
        if (!loginDTO.getPhone().matches(RegexUtil.REGEX_PHONE)) {
            throw new BusinessException(MessageConstants.PHONE_ERROR);
        }

        // 校验验证码是否符合格式
        if (!loginDTO.getCode().matches(RegexUtil.REGEX_CODE_6)) {
            throw new BusinessException(MessageConstants.CODE_ERROR);
        }

        //redis中拿出该手机号的验证码
        String code = cacheClient.get(PrefixConstants.CODE_PREFIX + loginDTO.getPhone());
        if (code == null) {
            throw new BusinessException(MessageConstants.CODE_ERROR);
        }

        //校验验证码是否正确
        if (!loginDTO.getCode().equals(code)) {
            // 失败计数（与管理员密码登录共用 LoginFailCounter）：达到上限即作废当前验证码，
            // 需重新获取验证码才能继续尝试。否则 6 位验证码在有效期内可被无限枚举，可暴力破解任意手机号登录
            long fails = loginFailCounter.record(PrefixConstants.USER_LOGIN_FAIL + loginDTO.getPhone(),
                    NumConstants.CODE_EXPIRE_MINUTES);
            if (fails >= NumConstants.USER_LOGIN_FAIL_LIMIT) {
                cacheClient.delete(PrefixConstants.CODE_PREFIX + loginDTO.getPhone());
                throw new BusinessException(MessageConstants.CODE_FAIL_LIMIT);
            }
            throw new BusinessException(MessageConstants.CODE_ERROR);
        }

        // 校验用户是否存在（不过滤状态：禁用用户必须能查到，才能明确拒绝而非误走注册分支）
        User user = lambdaQuery().eq(User::getPhone, loginDTO.getPhone()).one();
        //不存在则注册
        Long id;
        if (user == null) {
            //随机生成10位用户名
            String username = RandomUtil.randomString(NumConstants.USERNAME_LENGTH);
            User newUser = new User();
            newUser.setPhone(loginDTO.getPhone());
            newUser.setUsername(username);
            save(newUser);
            id = newUser.getId();
        } else if (UserStatus.DISABLED.equals(user.getStatus())) {
            //存在但被禁用：明确拒绝登录（防止禁用用户走注册分支绕过禁用）
            throw new BusinessException(MessageConstants.USER_DISABLED);
        } else {
            //存在取出用户名
            id = user.getId();
        }

        //成功生成token
        Map<String, Object> claims = new HashMap<>();
        //生成DeviceId
        String deviceId = RandomUtil.randomNumbers(NumConstants.DEVICE_ID_LENGTH);

        //将用户ID和设备ID添加到claims中
        claims.put("id", id.toString());
        claims.put("deviceId", deviceId);
        String accessToken = jwtUtil.createAccessToken(claims);
        String refreshToken = jwtUtil.createRefreshToken(claims);
        // 设备 RT 存入 Hash（RMapCache 支持 field 级独立过期，兼容任意 Redis 版本）
        cacheClient.mapPut(PrefixConstants.USER_TOKENS + id, deviceId, refreshToken,
                jwtProperties.getRefreshExpiration(), TimeUnit.MILLISECONDS);
        // 登录成功
        log.info("用户登录成功，手机号：{}", loginDTO.getPhone());

        // 清除Redis中的验证码与失败计数
        cacheClient.delete(PrefixConstants.CODE_PREFIX + loginDTO.getPhone());
        loginFailCounter.clear(PrefixConstants.USER_LOGIN_FAIL + loginDTO.getPhone());

        //返回token给前端
        Map<String, Object> map = new HashMap<>();
        map.put("accessToken", accessToken);
        map.put("refreshToken", refreshToken);
        map.put("deviceId", deviceId);
        return map;
    }

    /**
     * 退出登录（当前设备），删除 RT 并拉黑 AT
     */
    @Override
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.LOGOUT, descriptionEL = "'退出登录'")
    public void logout() {
        Long id = IdHolder.getId();
        Long deviceId = DeviceHolder.getDeviceId();
        // 删除当前设备 RT：必须经 RMapCache 删除（RMapCache 写入的 field 经 Redisson codec 编码，
        // StringRedisTemplate 的 opsForHash().delete 按原始字符串匹配会删除失败）
        cacheClient.mapRemove(PrefixConstants.USER_TOKENS + id, deviceId.toString());
        blacklistAccessToken();
        log.info("{} 退出成功", id);
    }

    /**
     * 踢掉所有设备（含当前设备），删除所有 RT 并拉黑当前 AT
     */
    @Override
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.LOGOUT_ALL, descriptionEL = "'踢出所有设备'")
    public void logoutAll() {
        Long id = IdHolder.getId();
        // 删除整个 Hash，所有设备的 RT 全部失效
        cacheClient.delete(PrefixConstants.USER_TOKENS + id);
        blacklistAccessToken();
        log.info("{} 踢掉所有设备成功", id);
    }

    /**
     * 踢掉指定设备
     */
    @Override
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.LOGOUT_DEVICE, descriptionEL = "'踢出设备 ' + #deviceId")
    public void logoutDevice(Long deviceId) {
        Long id = IdHolder.getId();
        // 同上：RMapCache 写入的 field 需用 Redisson 删除，StringRedisTemplate 的 opsForHash().delete 会失败
        cacheClient.mapRemove(PrefixConstants.USER_TOKENS + id, deviceId.toString());
        if (deviceId.equals(DeviceHolder.getDeviceId())) {
            blacklistAccessToken();
        }
        log.info("{} 踢掉设备 {} 成功", id, deviceId);
    }

    /**
     * 获取当前登录用户信息
     */
    @Override
    @Cacheable(cacheNames = CacheConstants.USER_PRIVATE_INFO, key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.USER_VIEW_SELF, descriptionEL = "'查看个人资料'")
    public UserPrivateVO getPrivateInfo() {
        // 校验用户是否存在
        User user = checkUser();
        // 缓存 objectName，响应出口由 OssUrlResponseBodyAdvice 统一签名
        String avatar;
        if (user.getAvatar() == null || user.getAvatar().isEmpty()) {
            avatar = DefaultConstants.DEFAULT_AVATAR;
        } else {
            avatar = user.getAvatar();
        }
        UserPrivateVO userPrivateVO = BeanUtil.copyProperties(user, UserPrivateVO.class);
        userPrivateVO.setAvatar(avatar);
        log.info("用户 {} 查看个人资料", IdHolder.getId());
        return userPrivateVO;
    }

    /**
     * 获取他人的公开信息
     *
     * @param userId 用户ID
     * @return 用户公开信息
     */
    @Override
    @Cacheable(cacheNames = CacheConstants.USER_PUBLIC_INFO, key = "#userId")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#userId", action = OperationActionEnum.USER_VIEW_OTHER,
            descriptionEL = "'查看用户 ' + #userId + ' 的公开信息'")
    public UserPublicVO getUserPublicInfo(Long userId) {
        log.info("查看用户 {} 的公开信息", userId);
        // 校验用户是否存在
        User user = lambdaQuery().eq(User::getId, userId).one();
        if (user == null) {
            throw new BusinessException(MessageConstants.USER_NOT_EXIST);
        }
        // 缓存 objectName，响应出口由 OssUrlResponseBodyAdvice 统一签名
        String avatar;
        if (user.getAvatar() == null || user.getAvatar().isEmpty()) {
            avatar = DefaultConstants.DEFAULT_AVATAR;
        } else {
            avatar = user.getAvatar();
        }
        UserPublicVO userPublicVO = BeanUtil.copyProperties(user, UserPublicVO.class);
        userPublicVO.setAvatar(avatar);
        log.info("用户 {} 查看了用户 {} 的公开资料", IdHolder.getId(), userId);
        return userPublicVO;
    }

    /**
     * 更新用户信息
     *
     * @param username 用户名
     * @param avatar   头像文件
     */
    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.USER_PUBLIC_INFO, key = "T(com.rambo.common.context.IdHolder).getId()"),
            @CacheEvict(cacheNames = CacheConstants.USER_PRIVATE_INFO, key = "T(com.rambo.common.context.IdHolder).getId()")
    })
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.USER_UPDATE_INFO, descriptionEL = "'修改个人资料'")
    public void updateInfo(String username, MultipartFile avatar) {
        // 校验用户是否存在
        User user = checkUser();

        boolean hasUsername = username != null && !username.isBlank();
        boolean hasAvatar = avatar != null && !avatar.isEmpty();

        // 至少需要一个更新项
        if (!hasUsername && !hasAvatar) {
            throw new BusinessException(MessageConstants.UPDATE_INFO_EMPTY);
        }

        // 更新用户名
        if (hasUsername) {
            user.setUsername(username);
        }

        // 更新头像
        String oldAvatar = null;
        String objectName = null;
        if (hasAvatar) {
            validateAvatar(avatar);
            oldAvatar = user.getAvatar();
            objectName = aliyunOssUtil.upload(avatar);
            user.setAvatar(objectName);
        }

        try {
            updateById(user);
        } catch (Exception e) {
            log.error("更新用户信息失败，userId={}", IdHolder.getId(), e);
            // DB 更新失败：删除刚上传的新头像，避免孤儿文件
            // 注意：清理若再失败，绝不能掩盖原始 DB 异常，故单独 try-catch 仅记录不抛出
            if (hasAvatar) {
                try {
                    aliyunOssUtil.deleteFile(objectName);
                } catch (Exception cleanupEx) {
                    log.error("用户信息更新失败后头像清理也失败（可能产生孤儿文件），userId={}，objectName={}",
                            IdHolder.getId(), objectName, cleanupEx);
                }
            }
            throw e;
        }

        // DB 更新成功后才删除旧头像（afterCommit 无事务时立即执行），避免 DB 失败时旧头像被误删
        if (hasAvatar) {
            final String old = oldAvatar;
            TransactionUtils.afterCommit(() -> aliyunOssUtil.deleteFile(old));
        }
        log.info("用户 {} 更新资料成功", IdHolder.getId());
    }

    /**
     * 认证用户
     *
     * @param userAuthDTO 认证请求参数
     */
    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.USER_PUBLIC_INFO, key = "T(com.rambo.common.context.IdHolder).getId()"),
            @CacheEvict(cacheNames = CacheConstants.USER_PRIVATE_INFO, key = "T(com.rambo.common.context.IdHolder).getId()")
    })
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.USER_AUTH, descriptionEL = "'实名认证'")
    public void auth(UserAuthDTO userAuthDTO) {
        // 校验用户是否存在
        User user = checkUser();
        // 校验用户是否已认证（认证状态与账号状态正交：禁用用户认证状态不丢失）
        if (user.getAuthStatus() == UserAuthStatus.VERIFIED) {
            throw new BusinessException(MessageConstants.USER_CONFIRMED);
        }

        //调用学生表认证学生是否存在
        Student student = studentService.lambdaQuery().eq(Student::getStudentId, userAuthDTO.getStudentId())
                .one();
        if (student == null) {
            throw new BusinessException(MessageConstants.STUDENT_AUTH_FAILED);
        }
        //是否绑定用户ID
        if (student.getUserId() != null) {
            throw new BusinessException(MessageConstants.STUDENT_IS_CONFIRMED);
        }
        //校验真实姓名是否正确
        if (!student.getRealName().equals(userAuthDTO.getRealName())) {
            throw new BusinessException(MessageConstants.STUDENT_AUTH_FAILED);
        }
        // 认证用户（只改认证状态，账号状态保持不动）
        user.setAuthStatus(UserAuthStatus.VERIFIED);
        user.setStudentId(userAuthDTO.getStudentId());
        user.setRealName(userAuthDTO.getRealName());

        try {
            updateById(user);
        } catch (DuplicateKeyException e) {
            log.warn("学生认证冲突，studentId={}，可能已被其他账号绑定", userAuthDTO.getStudentId());
            throw new BusinessException(MessageConstants.STUDENT_IS_CONFIRMED);
        }

        // 认证状态已变更：立即失效 NoAuthInterceptor 的状态快照，下次请求回源拿 VERIFIED
        cacheClient.delete(PrefixConstants.USER_STATUS + user.getId());

        //学生表绑定用户ID
        student.setUserId(user.getId());
        studentService.updateById(student);

        // 认证成功
        log.info("用户认证成功，手机号：{}", user.getPhone());
    }

    /**
     * 校验用户是否存在
     *
     * @return 用户对象
     */
    private User checkUser() {
        Long id = IdHolder.getId();
        User user = lambdaQuery().eq(User::getId, id)
                .one();
        if (user == null) {
            throw new BusinessException(MessageConstants.USER_NOT_EXIST);
        }
        return user;
    }

    /**
     * 校验头像文件
     *
     * @param file 头像文件
     */
    private void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(MessageConstants.AVATAR_EMPTY);
        }
        // 大小
        if (file.getSize() > NumConstants.AVATAR_IMAGE_MAX_SIZE) {
            throw new BusinessException(MessageConstants.AVATAR_SIZE_ERROR);
        }
        // 格式
        String original = file.getOriginalFilename();
        if (original == null || !original.contains(".")) {
            throw new BusinessException(MessageConstants.AVATAR_FORMAT_ERROR);
        }
        String suffix = original.substring(original.lastIndexOf(".")).toLowerCase();
        if (!NumConstants.AVATAR_IMAGE_ALLOWED_TYPES.contains(suffix)) {
            throw new BusinessException(MessageConstants.AVATAR_FORMAT_ERROR);
        }
    }

    /**
     * 将当前请求的 accessToken 加入黑名单，TTL 为 token 剩余有效期
     */
    private void blacklistAccessToken() {
        String token = AccessTokenHolder.getToken();
        if (token == null) return;
        Date exp = jwtUtil.getExpiration(token);
        long ttl = exp.getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            cacheClient.set(
                    PrefixConstants.AT_BLACKLIST + token, "1", ttl, TimeUnit.MILLISECONDS);
        }
    }
}
