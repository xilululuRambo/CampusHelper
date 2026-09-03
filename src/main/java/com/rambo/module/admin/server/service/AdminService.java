package com.rambo.module.admin.server.service;

import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AddAdminDTO;
import com.rambo.module.admin.pojo.dto.AdminLoginDTO;
import com.rambo.module.admin.pojo.dto.AdminQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUpdateInfoDTO;
import com.rambo.module.admin.pojo.entity.Admin;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.admin.pojo.vo.AdminVO;

import java.util.Map;

public interface AdminService extends IService<Admin> {
    /**
     * 管理员登录
     * @param adminLoginDTO 管理员登录DTO
     * @return 登录成功后的token等信息
     */
    Map<String, Object> login(AdminLoginDTO adminLoginDTO);

    /**
     * 超级管理员新增管理员
     * @param addAdminDTO 新增管理员DTO
     */
    void add(AddAdminDTO addAdminDTO);

    /**
     * 查询管理员详情
     * @param id 管理员ID
     * @return 管理员详情
     */
    AdminVO getDetail(Long id);

    /**
     * 超级管理员重置密码
     * @param id 管理员ID
     * @param newPassword 新密码
     */
    void resetPassword(Long id, String newPassword);

    /**
     * 超级管理员修改管理员信息
     * @param id 管理员ID
     * @param adminUpdateInfoDTO 管理员信息DTO
     */
    void updateInfo(Long id, AdminUpdateInfoDTO adminUpdateInfoDTO);

    /**
     * 退出登录
     */
    void logout();

    /**
     * 超级管理员退出其他账号登录
     */
    void disable(Long id);

    /**
     * 超级管理员分页查询管理员列表
     * @param adminQueryDTO 分页与筛选条件（账号精确/姓名模糊/状态/角色）
     * @return 管理员分页结果（不含密码）
     */
    PageResult<AdminVO> listAll(AdminQueryDTO adminQueryDTO);
}
