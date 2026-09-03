package com.rambo.module.admin.server.service;

import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AdminUserCreditDTO;
import com.rambo.module.admin.pojo.dto.AdminUserPointsDTO;
import com.rambo.module.admin.pojo.dto.AdminUserQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUserUpdateDTO;
import com.rambo.module.admin.pojo.vo.AdminUserInfoVO;
import com.rambo.module.admin.pojo.vo.AdminUserListItemVO;
import com.rambo.module.user.enums.UserStatus;

public interface AdminUserService {
    /**
     * 分页查询用户列表
     * @param adminUserQueryDTO  分页查询用户列表参数
     * @return  分页查询用户列表（精简字段，详情见 getDetail）
     */
    PageResult<AdminUserListItemVO> getList(AdminUserQueryDTO adminUserQueryDTO);

    /**
     * 查询用户详情
     * @param id 用户ID
     * @return 用户详情
     */
    AdminUserInfoVO getDetail(Long id);

    /**
     * 启用禁用用户
     * @param id 用户ID
     */
    void enable(Long id, UserStatus status);

    /**
     * 修改用户基本信息（用户名/手机号/真实姓名，均需唯一性+格式校验）
     * @param id 用户ID
     * @param updateDTO 可修改字段（至少提供一个）
     */
    void updateUser(Long id, AdminUserUpdateDTO updateDTO);

    /**
     * 调整用户积分（正加负扣，积分不得为负；乐观锁防并发超扣）
     * @param id 用户ID
     * @param pointsDTO 变动值与原因
     */
    void adjustPoints(Long id, AdminUserPointsDTO pointsDTO);

    /**
     * 调整用户信誉分（调整后须在0-100区间；乐观锁防并发）
     * @param id 用户ID
     * @param creditDTO 变动值与原因
     */
    void adjustCredit(Long id, AdminUserCreditDTO creditDTO);

    /**
     * 强制下线：删除该用户全部设备的 Refresh Token，不改账号状态
     * @param id 用户ID
     */
    void kick(Long id);
}
