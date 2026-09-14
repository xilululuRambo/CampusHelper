package com.rambo.module.admin.server.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.infrastructure.auth.BCryptUtil;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.infrastructure.auth.LoginFailCounter;
import com.rambo.common.utils.RegexUtil;
import com.rambo.infrastructure.auth.AccessTokenHolder;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.config.JwtProperties;
import com.rambo.common.context.RoleHolder;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import com.rambo.module.admin.pojo.dto.AddAdminDTO;
import com.rambo.module.admin.pojo.dto.AdminLoginDTO;
import com.rambo.module.admin.pojo.dto.AdminQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUpdateInfoDTO;
import com.rambo.module.admin.pojo.entity.Admin;
import com.rambo.module.admin.pojo.vo.AdminVO;
import com.rambo.module.admin.server.mapper.AdminMapper;
import com.rambo.module.admin.server.service.AdminService;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements AdminService {
    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private JwtProperties jwtProperties;
    @Resource
    private LoginFailCounter loginFailCounter;


    /**
     * 管理员登录
     *
     * @param adminLoginDTO 管理员登录DTO
     * @return 登录结果
     */
    @Override
    public Map<String, Object> login(AdminLoginDTO adminLoginDTO) {
        //校验登录DTO
        validateLoginDTO(adminLoginDTO.getAccount(), adminLoginDTO.getPassword());

        //登录失败锁定：此处不做 hasKey 判断——失败计数内部按计数 >= 5 才抛锁定。
        //若用 hasKey 判断，第 1 次失败即建 key，第 2 次请求（无论密码对错）直接命中"已锁定"，
        //攻击者 1 次错误密码即可锁死账号 15 分钟（可被 DoS），"连续 5 次"阈值永远达不到。
        //计数机制复用 LoginFailCounter（与用户验证码登录共用同一套实现）
        String failKey = PrefixConstants.ADMIN_LOGIN_FAIL + adminLoginDTO.getAccount();

        //查找数据库是否存在该账号的管理员，且状态为正常（禁用账号不允许登录）
        Admin admin = lambdaQuery()
                .eq(Admin::getAccount, adminLoginDTO.getAccount())
                .eq(Admin::getStatus, AdminStatus.NORMAL)
                .one();

        //判空（账号不存在同样计数，防撞库探测）
        if (admin == null) {
            recordLoginFail(failKey);
            throw new BusinessException(MessageConstants.ADMIN_LOGIN_ERROR);
        }

        //判断密码是否正确
        if (!BCryptUtil.check(adminLoginDTO.getPassword(), admin.getPassword())) {
            recordLoginFail(failKey);
            throw new BusinessException(MessageConstants.ADMIN_LOGIN_ERROR);
        }

        //锁定中即使密码正确也拒绝登录（锁定 15 分钟内不可绕过；成功路径必须复查，
        //否则"连续 5 次失败"后第 6 次蒙对密码即清空计数，锁定形同虚设）
        if (loginFailCounter.count(failKey) >= NumConstants.ADMIN_LOGIN_FAIL_LIMIT) {
            throw new BusinessException(MessageConstants.ADMIN_LOGIN_LOCKED);
        }

        //登录成功，清除失败计数
        loginFailCounter.clear(failKey);

        //生成载荷Claims：携带角色标记，供 JwtInterceptor 统一分流（超管与普通管理员分开签发）
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", admin.getId().toString());
        claims.put("role", isSuperRole(admin.getRole())
                ? EnumConstants.ROLE_SUPER_ADMIN : EnumConstants.ROLE_ADMIN);

        //生成双token
        String accessToken = jwtUtil.createAccessToken(claims);
        String refreshToken = jwtUtil.createRefreshToken(claims);

        //存入redis（TTL 单位必须为毫秒，与 jwt.refresh-expiration 配置一致，否则 7 天会被存成 7 秒）
        cacheClient.set(PrefixConstants.ADMIN_TOKENS + admin.getId(), refreshToken,
                jwtProperties.getRefreshExpiration(), TimeUnit.MILLISECONDS);

        //添加到map中
        Map<String, Object> map = new HashMap<>();
        map.put("accessToken", accessToken);
        map.put("refreshToken", refreshToken);

        //返回token
        return map;

    }

    /**
     * 超级管理员新增管理员
     *
     * @param addAdminDTO 新增管理员DTO
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "null",
            action = OperationActionEnum.ADMIN_ADD, descriptionEL = "'新增管理员 account=' + #addAdminDTO.getAccount()")
    public void add(AddAdminDTO addAdminDTO) {
        //校验登录DTO
        validateLoginDTO(addAdminDTO.getAccount(), addAdminDTO.getPassword());

        //判断数据库是否存在该账号的管理员
        Admin admin = lambdaQuery()
                .eq(Admin::getAccount, addAdminDTO.getAccount())
                .one();

        //判断当前登录用户是否为超级管理员
        requireSuper();

        //判断账号是否存在
        if (admin != null) {
            throw new BusinessException(MessageConstants.ADMIN_ACCOUNT_EXIST);
        }

        //新增管理员：状态默认正常，角色默认普通管理员（超管可指定）
        admin = new Admin();
        admin.setAccount(addAdminDTO.getAccount());
        admin.setPassword(BCryptUtil.encrypt(addAdminDTO.getPassword()));
        admin.setName(validateName(addAdminDTO.getName()));
        admin.setPhone(validatePhone(addAdminDTO.getPhone()));
        admin.setStatus(addAdminDTO.getStatus() == null ? AdminStatus.NORMAL : addAdminDTO.getStatus());
        admin.setRole(addAdminDTO.getRole() == null ? AdminRole.NORMAL : addAdminDTO.getRole());

        //入库
        save(admin);
    }

    /**
     * 查询管理员详情
     *
     * @param id 管理员ID
     * @return 管理员详情
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_VIEW, descriptionEL = "'查看管理员详情 id=' + #id")
    public AdminVO getDetail(Long id) {
        log.info("查看管理员详情 id={}", id);
        //查询管理员详情
        Admin admin = getAdminById(id);

        //返回管理员详情
        return toVO(admin);
    }

    /**
     * 超级管理员重置密码
     *
     * @param id          管理员ID
     * @param newPassword 新密码
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_RESET_PASSWORD, descriptionEL = "'重置管理员密码 id=' + #id")
    public void resetPassword(Long id, String newPassword) {
        //校验新密码格式
        if (!newPassword.matches(RegexUtil.REGEX_PASSWORD)) {
            throw new BusinessException(MessageConstants.ADMIN_PASSWORD_INVALID);
        }

        //校验当前登录用户是否为超级管理员
        requireSuper();

        //查询管理员详情
        Admin admin = getAdminById(id);

        //重置密码
        admin.setPassword(BCryptUtil.encrypt(newPassword));
        updateById(admin);

        //退出该账号登录
        disable(id);
    }

    /**
     * 超级管理员修改管理员信息
     *
     * @param id                 管理员ID
     * @param adminUpdateInfoDTO 管理员信息DTO
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_UPDATE_INFO, descriptionEL = "'修改管理员资料 id=' + #id")
    public void updateInfo(Long id, AdminUpdateInfoDTO adminUpdateInfoDTO) {
        //超级管理员专属操作
        requireSuper();

        //判断用户是否存在
        Admin admin = getAdminById(id);

        //禁止操作自己（禁用/降级自身会导致账号自锁）
        if (admin.getId().equals(IdHolder.getId())
                && (AdminStatus.DISABLED.equals(adminUpdateInfoDTO.getStatus())
                || (adminUpdateInfoDTO.getRole() != null && !admin.getRole().equals(adminUpdateInfoDTO.getRole())))) {
            throw new BusinessException(MessageConstants.ADMIN_CANNOT_OPERATE_SELF);
        }

        //校验姓名、手机号格式并更新
        admin.setName(validateName(adminUpdateInfoDTO.getName()));
        admin.setPhone(validatePhone(adminUpdateInfoDTO.getPhone()));
        admin.setStatus(adminUpdateInfoDTO.getStatus());
        if (adminUpdateInfoDTO.getRole() != null) {
            admin.setRole(adminUpdateInfoDTO.getRole());
        }

        //改为禁用则立即删除其 Refresh Token（踢下线，禁止续期），并写入禁用标记——
        //AT 是无状态 JWT 无法逐个拉黑，标记由 JwtInterceptor 每次请求校验，保证禁用立即生效；
        //改为启用则清除禁用标记，恢复访问（与用户侧 USER_DISABLED 语义对齐）
        if (AdminStatus.DISABLED.equals(adminUpdateInfoDTO.getStatus())) {
            cacheClient.delete(PrefixConstants.ADMIN_TOKENS + admin.getId());
            cacheClient.set(PrefixConstants.ADMIN_DISABLED + admin.getId(), "1");
        } else {
            cacheClient.delete(PrefixConstants.ADMIN_DISABLED + admin.getId());
        }

        //更新管理员信息
        updateById(admin);
    }

    /**
     * 退出登录
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.LOGOUT, descriptionEL = "'管理员退出登录'")
    public void logout() {
        Long id = IdHolder.getId();
        //删除管理员Refresh token，先终结会话
        cacheClient.delete(PrefixConstants.ADMIN_TOKENS + id);
        //将当前 accessToken 加入黑名单，实现即时失效
        blacklistAccessToken();
    }

    /**
     * 超级管理员退出其他账号登录
     * @param id 管理员ID
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.ADMIN,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_DISABLE, descriptionEL = "'禁用管理员 id=' + #id")
    public void disable(Long id) {
        //判断用户是否存在
        getAdminById(id);

        //判断当前登录用户是否为超级管理员
        requireSuper();

        //删除目标管理员的 Refresh Token，使其无法续期（其 accessToken 最长存活至过期）
        cacheClient.delete(PrefixConstants.ADMIN_TOKENS + id);
    }

    /**
     * 超级管理员分页查询管理员列表
     *
     * @param adminQueryDTO 分页与筛选条件（账号精确/姓名模糊/状态/角色）
     * @return 管理员分页结果（不含密码）
     */
    @Override
    public PageResult<AdminVO> listAll(AdminQueryDTO adminQueryDTO) {
        //超级管理员专属
        requireSuper();

        //构造分页查询
        Page<Admin> page = new Page<>(adminQueryDTO.getPageNum(), adminQueryDTO.getPageSize());
        Page<Admin> adminPage = lambdaQuery()
                .eq(StringUtils.hasText(adminQueryDTO.getAccount()), Admin::getAccount, adminQueryDTO.getAccount())
                .like(StringUtils.hasText(adminQueryDTO.getName()), Admin::getName, adminQueryDTO.getName())
                .eq(adminQueryDTO.getStatus() != null, Admin::getStatus, adminQueryDTO.getStatus())
                .eq(adminQueryDTO.getRole() != null, Admin::getRole, adminQueryDTO.getRole())
                .orderByDesc(Admin::getCreateTime)
                .page(page);

        //无数据直接返回空分页
        if (adminPage.getTotal() == 0) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        //实体转VO（剔除密码等敏感字段）
        List<AdminVO> records = adminPage.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return new PageResult<>(adminPage.getTotal(), records);
    }


    /**
     * 根据ID查询管理员，不存在时抛出异常
     *
     * @param id 管理员ID
     * @return 管理员信息
     */
    private Admin getAdminById(Long id) {
        Admin admin = lambdaQuery()
                .eq(Admin::getId, id)
                .one();
        if (admin == null) {
            throw new BusinessException(MessageConstants.ADMIN_NOT_FOUND);
        }
        return admin;
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

    /**
     * 校验姓名格式（可选字段，为 null 时跳过校验）
     *
     * @param name 管理员姓名
     * @return 校验通过后的姓名
     */
    private String validateName(String name) {
        //判空后校验姓名格式是否为 2-4 位中文
        if (name != null && !name.matches(RegexUtil.REGEX_NAME)) {
            throw new BusinessException(MessageConstants.ADMIN_NAME_INVALID);
        }
        return name;
    }

    /**
     * 校验手机号格式（可选字段，为 null 时跳过校验）
     *
     * @param phone 管理员手机号
     * @return 校验通过后的手机号
     */
    private String validatePhone(String phone) {
        //判空后校验手机号格式是否为 11 位数字
        if (phone != null && !phone.matches(RegexUtil.REGEX_PHONE)) {
            throw new BusinessException(MessageConstants.ADMIN_PHONE_INVALID);
        }
        return phone;
    }

    /**
     * 记录一次登录失败；达到 5 次时抛锁定异常（key 15 分钟过期）。
     * 计数机制复用 {@link LoginFailCounter}（与用户验证码登录同一套），策略在本层：连续 5 次锁定 15 分钟
     */
    private void recordLoginFail(String failKey) {
        long fails = loginFailCounter.record(failKey, NumConstants.ADMIN_LOGIN_LOCK_MINUTES);
        if (fails >= NumConstants.ADMIN_LOGIN_FAIL_LIMIT) {
            throw new BusinessException(MessageConstants.ADMIN_LOGIN_LOCKED);
        }
    }

    /**
     * 实体转 VO（剔除密码等敏感字段）
     */
    private AdminVO toVO(Admin admin) {
        AdminVO adminVO = new AdminVO();
        adminVO.setId(admin.getId());
        adminVO.setAccount(admin.getAccount());
        adminVO.setName(admin.getName());
        adminVO.setPhone(admin.getPhone());
        adminVO.setStatus(admin.getStatus());
        adminVO.setRole(admin.getRole());
        return adminVO;
    }

    /**
     * 断言当前登录管理员为超级管理员，否则抛出无权限异常
     * 语义：命名为命令式（require），避免布尔谓词命名（isXxx）携带抛异常的副作用
     */
    private void requireSuper() {
        if (!EnumConstants.ROLE_SUPER_ADMIN.equals(RoleHolder.getRole())) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }
    }

    /**
     * 指定角色是否为超级管理员（token 角色分发的判定依据）
     */
    private boolean isSuperRole(AdminRole role) {
        return AdminRole.SUPER.equals(role);
    }

    /**
     * 校验账号密码格式
     *
     * @param account  管理员账号
     * @param password 管理员密码
     */
    private void validateLoginDTO(String account, String password) {
        //正则表达式校验账号是否为数字格式，且长度为11位
        if (!account.matches(RegexUtil.REGEX_ACCOUNT)) {
            throw new BusinessException(MessageConstants.ADMIN_ACCOUNT_INVALID);
        }
        //校验密码格式是否为 6-20 位数字、字母
        if (!password.matches(RegexUtil.REGEX_PASSWORD)) {
            throw new BusinessException(MessageConstants.ADMIN_PASSWORD_INVALID);
        }
    }
}
