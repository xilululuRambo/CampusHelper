package com.rambo.module.user.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.user.pojo.dto.LoginDTO;
import com.rambo.module.user.pojo.dto.UserAuthDTO;
import com.rambo.module.user.pojo.vo.UserPrivateVO;
import com.rambo.module.user.pojo.vo.UserPublicVO;
import com.rambo.module.user.pojo.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface UserService extends IService<User> {


    /**
     * 发送验证码
     * @param phone 手机号
     */
    void sendCode(String phone);


    /**
     * 用户登录与注册
     * @param loginDTO 登录请求参数
     */
    Map<String, Object> login(LoginDTO loginDTO);


    /**
     * 退出登录（当前设备）
     */
    void logout();

    /**
     * 踢掉所有设备（含当前设备）
     */
    void logoutAll();

    /**
     * 踢掉指定设备
     * @param deviceId 设备ID
     */
    void logoutDevice(Long deviceId);

    /**
     * 获取自己的完整信息
     * @return 用户完整信息
     */
    UserPrivateVO getPrivateInfo();

    /**
     * 获取他人的公开信息
     * @param userId 用户ID
     * @return 用户公开信息
     */
    UserPublicVO getUserPublicInfo(Long userId);

    /**
     * 更新用户信息
     * @param username 用户名
     * @param avatar 头像文件
     */
    void updateInfo(String username, MultipartFile avatar);

    /**
     * 用户认证
     * @param userAuthDTO 认证请求参数
     */
    void auth(@Valid UserAuthDTO userAuthDTO);
}
