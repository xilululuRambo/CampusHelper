package com.rambo.module.user.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.user.pojo.dto.AddressDTO;
import com.rambo.module.user.pojo.entity.Address;
import com.rambo.module.user.pojo.vo.AddressVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface AddressService extends IService<Address> {
    /**
     * 保存用户地址
     *
     * @param addressDTO 地址请求参数DTO
     */
    void saveAddress(@Valid AddressDTO addressDTO);

    /**
     * 更新用户地址
     *
     * @param addressDTO 地址请求参数DTO
     */
    void updateAddress(@NotNull(message = "地址ID不能为空") Long addressId, @Valid AddressDTO addressDTO);

    /**
     * 获取用户地址列表
     *
     * @return 用户地址列表
     */
    List<AddressVO> getAddressList();

    /**
     * 删除用户地址
     *
     * @param addressId 地址ID
     */
    void deleteAddress(Long addressId);

    /**
     * 校验地址是否存在
     *
     * @param addressId 地址ID
     * @return 是否存在
     */
    boolean existsById(Long addressId);
}
