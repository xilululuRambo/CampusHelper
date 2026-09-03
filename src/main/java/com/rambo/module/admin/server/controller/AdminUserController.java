package com.rambo.module.admin.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.admin.pojo.dto.AdminUserCreditDTO;
import com.rambo.module.admin.pojo.dto.AdminUserPointsDTO;
import com.rambo.module.admin.pojo.dto.AdminUserQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminUserUpdateDTO;
import com.rambo.module.admin.pojo.vo.AdminUserInfoVO;
import com.rambo.module.admin.pojo.vo.AdminUserListItemVO;
import com.rambo.module.admin.server.service.AdminUserService;
import com.rambo.module.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/user")
@Slf4j
@Validated
@Tag(name = "管理用户接口")
public class AdminUserController{
    @Resource
    private AdminUserService adminUserService;


    /**
     * 分页查询用户列表
     * @return  分页查询用户列表（精简字段，详情见 /{id}）
     */
    @Operation(summary = "分页查询用户列表")
    @GetMapping("/list")
    public Result<PageResult<AdminUserListItemVO>> getList(AdminUserQueryDTO adminUserQueryDTO){
        log.info("分页查询用户列表: {}", adminUserQueryDTO);
        PageResult<AdminUserListItemVO> page = adminUserService.getList(adminUserQueryDTO);
        return Result.success(page);
    }

    /**
     * 查询用户详情
     * @param id 用户ID
     * @return 用户详情
     */
    @Operation(summary = "查询用户详情")
    @GetMapping("/{id}")
    public Result<AdminUserInfoVO> getDetail(@PathVariable Long id){
        log.info("查询用户详情: {}", id);
        AdminUserInfoVO userVO = adminUserService.getDetail(id);
        return Result.success(userVO);
    }

    /**
     * 修改用户基本信息（仅限 username/phone/realName）
     * @param id 用户ID
     * @param updateDTO 修改参数
     */
    @Operation(summary = "修改用户基本信息")
    @PutMapping("/{id}")
    public Result<Void> updateUser(@PathVariable Long id, @Valid @RequestBody AdminUserUpdateDTO updateDTO){
        log.info("修改用户基本信息: {}, {}", id, updateDTO);
        adminUserService.updateUser(id, updateDTO);
        return Result.success();
    }

    /**
     * 调整用户积分
     * @param id 用户ID
     * @param pointsDTO 调整参数
     */
    @Operation(summary = "调整用户积分")
    @PostMapping("/{id}/points")
    public Result<Void> adjustPoints(@PathVariable Long id, @Valid @RequestBody AdminUserPointsDTO pointsDTO){
        log.info("调整用户积分: {}, {}", id, pointsDTO);
        adminUserService.adjustPoints(id, pointsDTO);
        return Result.success();
    }

    /**
     * 调整用户信誉分
     * @param id 用户ID
     * @param creditDTO 调整参数
     */
    @Operation(summary = "调整用户信誉分")
    @PostMapping("/{id}/credit")
    public Result<Void> adjustCredit(@PathVariable Long id, @Valid @RequestBody AdminUserCreditDTO creditDTO){
        log.info("调整用户信誉分: {}, {}", id, creditDTO);
        adminUserService.adjustCredit(id, creditDTO);
        return Result.success();
    }

    /**
     * 强制下线用户
     * @param id 用户ID
     */
    @Operation(summary = "强制下线用户")
    @PostMapping("/{id}/kick")
    public Result<Void> kick(@PathVariable Long id){
        log.info("强制下线用户: {}", id);
        adminUserService.kick(id);
        return Result.success();
    }

    /**
     * 启用禁用用户
     * @param id 用户ID
     */
    @Operation(summary = "启用禁用用户")
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id, @NotNull(message = "状态不能为空") UserStatus status){
        log.info("启用禁用用户: {}, {}", id, status);
        adminUserService.enable(id, status);
        return Result.success();
    }
}
