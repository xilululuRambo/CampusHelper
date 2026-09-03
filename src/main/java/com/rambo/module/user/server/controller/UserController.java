package com.rambo.module.user.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.result.Result;
import com.rambo.module.user.pojo.dto.LoginDTO;
import com.rambo.module.user.pojo.dto.UserAuthDTO;
import com.rambo.module.user.pojo.vo.UserPrivateVO;
import com.rambo.module.user.pojo.vo.UserPublicVO;
import com.rambo.module.user.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/user")
@Slf4j
@Validated
@Tag(name = "用户接口", description = "用户相关接口")
public class UserController {

    @Resource
    private UserService userService;


    /**
     * 发送验证码
     *
     * @param phone 手机号
     * @return 发送成功返回成功结果
     */
    @PostMapping("/code")
    @Operation(summary = "发送验证码")
    public Result<Void> sendCode(@NotBlank(message = "手机号不能为空") @RequestParam("phone") String phone) {
        log.info("发送验证码，手机号：{}", phone);
        userService.sendCode(phone);
        return Result.success();
    }


    /**
     * 用户登录与注册
     *
     * @param loginDTO 登录请求参数
     * @return 登录成功返回成功结果
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录与注册")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO loginDTO) {
        // 脱敏打点：LoginDTO 含验证码（凭证），整体打印会落日志；仅记录手机号
        log.info("用户登录，手机号：{}", loginDTO.getPhone());
        Map<String, Object> map = userService.login(loginDTO);
        return Result.success(map);
    }

    /**
     * 退出登录
     *
     * @return 退出成功返回成功结果
     */
    @GetMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout() {
        log.info("用户退出登录");
        userService.logout();
        return Result.success();
    }

    /**
     * 踢掉所有设备（设备丢失后一键安全退出）
     */
    @PostMapping("/logout-all")
    @Operation(summary = "踢掉所有设备")
    public Result<Void> logoutAll() {
        log.info("用户踢掉所有设备");
        userService.logoutAll();
        return Result.success();
    }

    /**
     * 踢掉指定设备
     */
    @PostMapping("/logout-device")
    @Operation(summary = "踢掉指定设备")
    public Result<Void> logoutDevice(@RequestParam Long deviceId) {
        log.info("用户踢掉设备：{}", deviceId);
        userService.logoutDevice(deviceId);
        return Result.success();
    }

    /**
     * 获取当前登录用户自己的完整信息（私有）
     */
    @GetMapping("/me")
    @NoAuthAnnotation
    @Operation(summary = "获取我的信息")
    public Result<UserPrivateVO> getPrivateInfo() {
        return Result.success(userService.getPrivateInfo());
    }

    /**
     * 获取他人公开信息（头像、用户名、积分、信誉分等）
     */
    @GetMapping("/public/{userId}")
    @NoAuthAnnotation
    @Operation(summary = "获取用户公开信息")
    public Result<UserPublicVO> getUserPublicInfo(@PathVariable Long userId) {
        return Result.success(userService.getUserPublicInfo(userId));
    }

    /**
     * 更新用户信息
     *
     * @param username avatar 更新用户信息请求参数
     * @return 更新成功返回成功结果
     */
    @PutMapping
    @NoAuthAnnotation
    @Operation(summary = "更新用户信息")
    @Validated
    public Result<Void> updateInfo(@RequestParam(name = "username", required = false) String username,
                                   @RequestPart(name = "avatar", required = false) MultipartFile avatar) {
        log.info("更新用户信息，用户名：{}，头像：{}", username, avatar);
        userService.updateInfo(username, avatar);
        return Result.success();
    }

    /**
     * 用户认证
     *
     * @param userAuthDTO 用户认证请求参数
     * @return 认证成功返回成功结果
     */
    @PostMapping("/auth")
    @NoAuthAnnotation
    @Operation(summary = "用户认证")
    public Result<Void> auth(@Valid @RequestBody UserAuthDTO userAuthDTO) {
        log.info("用户认证，{}", userAuthDTO);
        userService.auth(userAuthDTO);
        return Result.success();
    }

}
