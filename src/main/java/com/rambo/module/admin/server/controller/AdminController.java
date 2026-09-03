package com.rambo.module.admin.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.common.context.IdHolder;
import com.rambo.module.admin.pojo.dto.AddAdminDTO;
import com.rambo.module.admin.pojo.dto.AdminLoginDTO;
import com.rambo.module.admin.pojo.dto.AdminQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUpdateInfoDTO;
import com.rambo.module.admin.pojo.vo.AdminVO;
import com.rambo.module.admin.server.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@Slf4j
@Validated
@Tag(name = "管理员接口")
public class AdminController {
    @Resource
    private AdminService adminService;

    /**
     * 管理员登录
     *
     * @param adminLoginDTO 管理员登录DTO
     * @return 登录成功后的token等信息
     */
    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO adminLoginDTO) {
        Map<String, Object> map = adminService.login(adminLoginDTO);
        return Result.success(map);
    }

    /**
     * 超级管理员新增管理员
     *
     * @param addAdminDTO 新增管理员DTO
     * @return 新增管理员结果
     */
    @Operation(summary = "超级管理员新增管理员")
    @PostMapping("/add")
    public Result<Map<String, Object>> add(@Valid @RequestBody AddAdminDTO addAdminDTO) {
        adminService.add(addAdminDTO);
        return Result.success();
    }

    /**
     * 获取当前登录管理员信息（前端会话锚点，与 /user/me 对称）
     *
     * @return 当前管理员详情
     */
    @Operation(summary = "获取当前管理员信息")
    @GetMapping("/me")
    public Result<AdminVO> me() {
        log.info("获取当前管理员信息");
        return Result.success(adminService.getDetail(IdHolder.getId()));
    }

    /**
     * 超级管理员分页查询管理员列表
     *
     * @param adminQueryDTO 分页与筛选条件（账号精确/姓名模糊/状态/角色）
     * @return 管理员分页结果
     */
    @Operation(summary = "超级管理员分页查询管理员列表")
    @GetMapping("/list")
    public Result<PageResult<AdminVO>> list(AdminQueryDTO adminQueryDTO) {
        log.info("超级管理员分页查询管理员列表: {}", adminQueryDTO);
        return Result.success(adminService.listAll(adminQueryDTO));
    }

    /**
     * 查询管理员详情
     *
     * @param id 管理员ID
     * @return 管理员详情
     */
    @Operation(summary = "查询管理员详情")
    @GetMapping("/{id}")
    public Result<AdminVO> getDetail(@PathVariable Long id) {
        log.info("查询管理员详情: {}", id);
        AdminVO adminVO = adminService.getDetail(id);
        return Result.success(adminVO);
    }

    /**
     * 超级管理员重置密码
     *
     * @param id          管理员ID
     * @param newPassword 新密码
     * @return 重置密码结果
     */
    @Operation(summary = "超级管理员重置密码")
    @PostMapping("/{id}/resetPassword")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestParam String newPassword) {
        log.info("超级管理员重置密码: {}", id);
        adminService.resetPassword(id, newPassword);
        return Result.success();
    }

    /**
     * 超级管理员修改管理员信息
     *
     * @param id                 管理员ID
     * @param adminUpdateInfoDTO 管理员信息DTO
     * @return 修改结果
     */
    @Operation(summary = "超级管理员修改管理员信息")
    @PostMapping("/{id}/update")
    public Result<Void> updateInfo(@PathVariable Long id, @Valid @RequestBody AdminUpdateInfoDTO adminUpdateInfoDTO) {
        adminService.updateInfo(id, adminUpdateInfoDTO);
        return Result.success();
    }

    /**
     * 退出登录
     *
     * @return 退出登录结果
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        log.info("退出登录");
        adminService.logout();
        return Result.success();
    }
}
