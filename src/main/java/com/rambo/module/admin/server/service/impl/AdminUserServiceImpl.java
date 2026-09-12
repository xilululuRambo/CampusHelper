package com.rambo.module.admin.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.result.PageResult;
import com.rambo.common.utils.RegexUtil;
import com.rambo.common.context.IdHolder;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.module.admin.pojo.dto.AdminUserCreditDTO;
import com.rambo.module.admin.pojo.dto.AdminUserPointsDTO;
import com.rambo.module.admin.pojo.dto.AdminUserQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUserUpdateDTO;
import com.rambo.module.admin.pojo.vo.AdminUserInfoVO;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.admin.pojo.vo.AdminUserListItemVO;
import com.rambo.module.admin.server.service.AdminUserService;
import com.rambo.module.user.enums.UserStatus;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
public class AdminUserServiceImpl implements AdminUserService {
    @Resource
    private UserService userService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private CacheManager cacheManager;

    @Resource
    private NotificationSender notificationSender;

    /**
     * 分页查询用户列表
     *
     * @param adminUserQueryDTO 分页查询用户列表参数
     * @return 分页查询用户列表（精简字段，详情见 getDetail）
     */
    @Override
    public PageResult<AdminUserListItemVO> getList(AdminUserQueryDTO adminUserQueryDTO) {
        //构造分页查询参数
        Page<User> page = new Page<>(adminUserQueryDTO.getPageNum(), adminUserQueryDTO.getPageSize());

        //查询用户列表分页
        Page<User> userPage = userService.lambdaQuery()
                .eq(adminUserQueryDTO.getPhone() != null, User::getPhone, adminUserQueryDTO.getPhone())
                .like(adminUserQueryDTO.getUsername() != null, User::getUsername, adminUserQueryDTO.getUsername())
                .like(adminUserQueryDTO.getRealName() != null, User::getRealName, adminUserQueryDTO.getRealName())
                .eq(adminUserQueryDTO.getStudentId() != null, User::getStudentId, adminUserQueryDTO.getStudentId())
                .eq(adminUserQueryDTO.getStatus() != null, User::getStatus, adminUserQueryDTO.getStatus())
                .eq(adminUserQueryDTO.getAuthStatus() != null, User::getAuthStatus, adminUserQueryDTO.getAuthStatus())
                .ge(adminUserQueryDTO.getStartTime() != null, User::getCreateTime, adminUserQueryDTO.getStartTime())
                .le(adminUserQueryDTO.getEndTime() != null, User::getCreateTime, adminUserQueryDTO.getEndTime())
                .page(page);

        //转换vo（仅拷贝精简字段，积分/余额等资产数据见详情接口）
        List<AdminUserListItemVO> adminUserList = userPage.getRecords().stream()
                .map(user -> {
                    AdminUserListItemVO userVO = new AdminUserListItemVO();
                    BeanUtil.copyProperties(user, userVO);
                    return userVO;
                }).toList();
        return new PageResult<>(userPage.getTotal(), adminUserList);
    }

    /**
     * 查询用户详情
     *
     * @param id 用户ID
     * @return 用户详情
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_VIEW, descriptionEL = "'管理员查看用户详情 id=' + #id")
    public AdminUserInfoVO getDetail(Long id) {
        log.info("管理员查看用户详情 id={}", id);
        //查询用户是否存在
        User user = getUserById(id);

        //实体转VO
        AdminUserInfoVO userVO = new AdminUserInfoVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 启用禁用用户
     * @param id 用户ID
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_ENABLE, descriptionEL = "'管理员启用/禁用用户 id=' + #id")
    public void enable(Long id, UserStatus status) {
        userService.lambdaUpdate()
                .eq(User::getId, id)
                .set(User::getStatus, status)
                .update();

        // 会话失效处理：
        // 1. 删除该用户全部设备的 Refresh Token（RT 有状态：按设备存于 Redis Hash，删 Hash 即全设备踢下线）
        // 2. 写入账号禁用标记：AT 是无状态 JWT 且服务端不知其值，无法逐个拉黑；
        //    标记由 JwtInterceptor 每次请求校验，保证禁用立即生效（即使 AT 未过期）
        if (UserStatus.DISABLED.equals(status)) {
            cacheClient.delete(PrefixConstants.USER_TOKENS + id);
            cacheClient.set(PrefixConstants.USER_DISABLED + id, "1");
        } else {
            // 解禁：清除禁用标记。AT 未过期可立即恢复访问；已过期需重新登录（RT 已在禁用时删除）
            cacheClient.delete(PrefixConstants.USER_DISABLED + id);
        }
        // 账号状态已变更（禁用/解禁双向）：立即失效 NoAuthInterceptor 的状态快照
        cacheClient.delete(PrefixConstants.USER_STATUS + id);

        //删除缓存：账号状态变更后，目标用户的私有/公开信息缓存必须立即失效，
        //否则接口仍会返回旧的 status 值（同时覆盖禁用与重新启用两种场景）
        evictUserCaches(id);
    }

    /**
     * 管理员修改用户基本信息
     *
     * @param id                 用户ID
     * @param updateDTO 修改参数
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_UPDATE, descriptionEL = "'管理员修改用户 id=' + #id")
    public void updateUser(Long id, AdminUserUpdateDTO updateDTO) {
        //至少提供一个可修改字段（资产/状态字段禁止走此接口，须走独立调整接口留审计痕迹）
        boolean hasUsername = StringUtils.hasText(updateDTO.getUsername());
        boolean hasPhone = StringUtils.hasText(updateDTO.getPhone());
        boolean hasRealName = StringUtils.hasText(updateDTO.getRealName());
        if (!hasUsername && !hasPhone && !hasRealName) {
            throw new BusinessException(MessageConstants.ADMIN_USER_UPDATE_EMPTY);
        }

        //查询用户是否存在
        User user = getUserById(id);

        //用户名：格式校验 + 唯一性预查（DB idx_username 约束兜底并发窗口）
        if (hasUsername) {
            String username = updateDTO.getUsername();
            if (!username.matches(RegexUtil.REGEX_USERNAME)) {
                throw new BusinessException(MessageConstants.USERNAME_INVALID);
            }
            Long count = userService.lambdaQuery()
                    .eq(User::getUsername, username)
                    .ne(User::getId, id)
                    .count();
            if (count > 0) {
                throw new BusinessException(MessageConstants.USERNAME_EXIST);
            }
            user.setUsername(username);
        }

        //手机号：格式校验 + 唯一性预查（DB phone 唯一约束兜底并发窗口）
        if (hasPhone) {
            String phone = updateDTO.getPhone();
            if (!phone.matches(RegexUtil.REGEX_PHONE)) {
                throw new BusinessException(MessageConstants.PHONE_ERROR);
            }
            Long count = userService.lambdaQuery()
                    .eq(User::getPhone, phone)
                    .ne(User::getId, id)
                    .count();
            if (count > 0) {
                throw new BusinessException(MessageConstants.PHONE_EXIST);
            }
            user.setPhone(phone);
        }

        //真实姓名：格式校验
        if (hasRealName) {
            String realName = updateDTO.getRealName();
            if (!realName.matches(RegexUtil.REGEX_NAME)) {
                throw new BusinessException(MessageConstants.NAME_INVALID);
            }
            user.setRealName(realName);
        }

        try {
            userService.updateById(user);
        } catch (DuplicateKeyException e) {
            //并发窗口内唯一键冲突（预查与更新之间被其他请求抢先占用），由 DB 约束兜底
            throw new BusinessException(MessageConstants.UNIQUE_CONFLICT);
        }

        //清理缓存：username 在公开信息、realName/phone 在私有信息
        evictUserCaches(id);

        //审计留痕（后续接入审计表时在此落库）
        log.info("管理员 {} 修改用户 {} 信息：{}", IdHolder.getId(), id, updateDTO);
    }

    /**
     * 管理员调整用户积分
     *
     * @param id                 用户ID
     * @param pointsDTO 调整参数
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_POINTS_ADJUST, descriptionEL = "'管理员调整用户积分 id=' + #id")
    public void adjustPoints(Long id, AdminUserPointsDTO pointsDTO) {
        //变动值不能为 0（无意义操作）
        if (pointsDTO.getDelta() == 0) {
            throw new BusinessException(MessageConstants.PARAM_ERROR);
        }

        //查询用户（含 version，乐观锁依赖）
        User user = getUserById(id);

        //积分下限保护：调整后不得为负
        int newPoints = user.getPoints() + pointsDTO.getDelta();
        if (newPoints < 0) {
            throw new BusinessException(MessageConstants.POINTS_INSUFFICIENT);
        }

        //带 version 更新触发乐观锁（OptimisticLockerInnerInterceptor 仅对 updateById(entity) 生效，
        //lambdaUpdate().set() 不会拼接 version 条件）；返回 false 即版本冲突，直接失败不重试，由管理员重新发起
        user.setPoints(newPoints);
        if (!userService.updateById(user)) {
            throw new BusinessException(MessageConstants.USER_UPDATE_CONFLICT);
        }

        //通知用户：与积分调整同一事务——成功必达、回滚必无（无 MQ 重试负担）；在线则 WebSocket 实时推送
        notificationSender.sendSync(NotificationMessage.builder()
                .userId(id)
                .type(NotificationType.POINTS_ADJUST)
                .content(String.format(MessageConstants.POINTS_ADJUST_NOTICE,
                        (pointsDTO.getDelta() > 0 ? "+" : "") + pointsDTO.getDelta(),
                        pointsDTO.getReason()))
                .refId(id)
                .build());

        //积分仅展示在私有信息中，只清私有缓存
        evictCache(CacheConstants.USER_PRIVATE_INFO, id);

        //审计留痕（后续接入积分流水表时在此落库）
        log.info("管理员 {} 调整用户 {} 积分 {}，原因：{}",
                IdHolder.getId(), id, pointsDTO.getDelta(), pointsDTO.getReason());
    }

    /**
     * 管理员调整用户信誉分
     *
     * @param id                 用户ID
     * @param creditDTO 调整参数
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_CREDIT_ADJUST, descriptionEL = "'管理员调整用户信用分 id=' + #id")
    public void adjustCredit(Long id, AdminUserCreditDTO creditDTO) {
        //变动值不能为 0（无意义操作）
        if (creditDTO.getDelta() == 0) {
            throw new BusinessException(MessageConstants.PARAM_ERROR);
        }

        //查询用户（含 version，乐观锁依赖）
        User user = getUserById(id);

        //信誉分区间约束：调整后须在 0-100（表默认 80）
        int newCredit = user.getCreditScore() + creditDTO.getDelta();
        if (newCredit < 0 || newCredit > 100) {
            throw new BusinessException(MessageConstants.CREDIT_OUT_OF_RANGE);
        }

        //带 version 更新触发乐观锁，返回 false 即版本冲突，直接失败不重试
        user.setCreditScore(newCredit);
        if (!userService.updateById(user)) {
            throw new BusinessException(MessageConstants.USER_UPDATE_CONFLICT);
        }

        //通知用户：与信誉分调整同一事务——成功必达、回滚必无；在线则 WebSocket 实时推送
        notificationSender.sendSync(NotificationMessage.builder()
                .userId(id)
                .type(NotificationType.CREDIT_ADJUST)
                .content(String.format(MessageConstants.CREDIT_ADJUST_NOTICE,
                        (creditDTO.getDelta() > 0 ? "+" : "") + creditDTO.getDelta(),
                        creditDTO.getReason()))
                .refId(id)
                .build());

        //信誉分同时展示在公开信息与私有信息中，两个缓存都要失效
        evictUserCaches(id);

        //审计留痕（后续接入信誉分流水表时在此落库）
        log.info("管理员 {} 调整用户 {} 信誉分 {}，原因：{}",
                IdHolder.getId(), id, creditDTO.getDelta(), creditDTO.getReason());
    }

    /**
     * 强制下线用户（不改账号状态）
     *
     * @param id 用户ID
     */
    @Override
    @Log(module = OperationModuleEnum.ADMIN, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "#id",
            action = OperationActionEnum.ADMIN_USER_KICK, descriptionEL = "'管理员踢下线用户 id=' + #id")
    public void kick(Long id) {
        //校验用户存在
        getUserById(id);

        //删除该用户全部设备的 Refresh Token（RT 有状态：按设备存于 Redis Hash，删 Hash 即全设备踢下线）；
        //AT 为无状态 JWT 服务端无法逐个拉黑，最长存活至过期，过期后无法续期被迫重新登录
        cacheClient.delete(PrefixConstants.USER_TOKENS + id);
        log.info("管理员 {} 强制下线用户 {}", IdHolder.getId(), id);
    }

    /**
     * 根据ID查询用户，不存在时抛出异常
     *
     * @param id 用户ID
     * @return 用户实体
     */
    private User getUserById(Long id) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(MessageConstants.USER_NOT_EXIST);
        }
        return user;
    }

    /**
     * 清理用户私有/公开信息缓存（资料/状态/信誉分变更后必须失效，否则接口仍返回旧值）
     *
     * @param id 用户ID
     */
    private void evictUserCaches(Long id) {
        evictCache(CacheConstants.USER_PRIVATE_INFO, id);
        evictCache(CacheConstants.USER_PUBLIC_INFO, id);
    }

    /**
     * 清理指定缓存中的用户条目
     *
     * @param cacheName 缓存名
     * @param id        用户ID
     */
    private void evictCache(String cacheName, Long id) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(id);
        }
    }
}
