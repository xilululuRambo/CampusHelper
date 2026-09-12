package com.rambo.module.user.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.user.enums.AddressStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.context.IdHolder;
import com.rambo.module.user.pojo.dto.AddressDTO;
import com.rambo.module.user.pojo.entity.Address;
import com.rambo.module.user.pojo.vo.AddressVO;
import com.rambo.module.user.server.mapper.AddressMapper;
import com.rambo.module.user.server.service.AddressService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {
    /**
     * 保存用户地址
     *
     * @param addressDTO 地址请求参数DTO
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConstants.ADDRESS_LIST, key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.ADDRESS,
            targetIdEL = "null", action = OperationActionEnum.ADDRESS_ADD, descriptionEL = "'新增收货地址'")
    public void saveAddress(AddressDTO addressDTO) {

        // 从线程本地获取用户ID
        Long userId = IdHolder.getId();
        // 最多只能保存3个地址，默认地址只能有一个
        if (lambdaQuery().eq(Address::getUserId, userId).count() >= NumConstants.ADDRESS_MAX_COUNT) {
            throw new BusinessException(MessageConstants.ADDRESS_MAX_COUNT);
        }
        // 保存地址前，先更新所有地址的默认状态为否
        if (AddressStatus.DEFAULT_ADDRESS.equals(addressDTO.getIsDefault())) {

            // 如果是默认地址，更新所有地址的默认状态为否
            lambdaUpdate()
                    .eq(Address::getUserId, userId)
                    .eq(Address::getIsDefault, AddressStatus.DEFAULT_ADDRESS)
                    .set(Address::getIsDefault, AddressStatus.NORMAL_ADDRESS)
                    .update();
        }
        Address address = BeanUtil.copyProperties(addressDTO, Address.class);
        address.setUserId(userId);
        try {
            save(address);
        } catch (Exception e) {
            log.error("新增收货地址失败，userId={}", userId, e);
            throw e;
        }
        log.info("用户 {} 新增收货地址成功", userId);
    }

    /**
     * 获取用户地址列表
     *
     * @return 用户地址列表
     */
    @Override
    @Cacheable(cacheNames = CacheConstants.ADDRESS_LIST,
            key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.ADDRESS,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.ADDRESS_VIEW, descriptionEL = "'查看收货地址列表'")
    public List<AddressVO> getAddressList() {
        // 从线程本地获取用户ID
        Long userId = IdHolder.getId();
        List<Address> addressList = lambdaQuery().eq(Address::getUserId, userId).orderByDesc(Address::getIsDefault).list();
        log.info("用户 {} 查看收货地址列表", userId);
        return BeanUtil.copyToList(addressList, AddressVO.class);
    }

    /**
     * 删除用户地址
     *
     * @param addressId 地址ID
     */
    @Override
    @CacheEvict(cacheNames = CacheConstants.ADDRESS_LIST,
            key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.ADDRESS,
            targetIdEL = "#addressId", action = OperationActionEnum.ADDRESS_DELETE,
            descriptionEL = "'删除收货地址 id=' + #addressId")
    public void deleteAddress(Long addressId) {
        // 从线程本地获取用户ID
        Long userId = IdHolder.getId();
        // 删除地址前，先检查地址是否存在
        Address address = getById(addressId);
        if (address == null) {
            throw new BusinessException(MessageConstants.ADDRESS_NOT_FOUND);
        }
        // 检查地址是否属于当前用户
        if (!address.getUserId().equals(userId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }
        try {
            removeById(addressId);
        } catch (Exception e) {
            log.error("删除收货地址失败，userId={}，addressId={}", userId, addressId, e);
            throw e;
        }
        log.info("用户 {} 删除收货地址 {} 成功", userId, addressId);
    }

    /**
     * 更新用户地址
     *
     * @param addressDTO 地址请求参数DTO
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConstants.ADDRESS_LIST,
            key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.ADDRESS,
            targetIdEL = "#addressId", action = OperationActionEnum.ADDRESS_UPDATE,
            descriptionEL = "'修改收货地址 id=' + #addressId")
    public void updateAddress(Long addressId, AddressDTO addressDTO) {
        // 从线程本地获取用户ID
        Long userId = IdHolder.getId();
        // 检查地址是否存在
        Address address = getById(addressId);
        if (address == null) {
            throw new BusinessException(MessageConstants.ADDRESS_NOT_FOUND);
        }
        // 检查地址是否属于当前用户
        if (!address.getUserId().equals(userId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }
        // 更新地址前，先更新所有地址的默认状态为否
        if (AddressStatus.DEFAULT_ADDRESS.equals(addressDTO.getIsDefault())) {

            // 如果是默认地址，更新所有地址的默认状态为否
            lambdaUpdate()
                    .eq(Address::getUserId, userId)
                    .eq(Address::getIsDefault, AddressStatus.DEFAULT_ADDRESS)
                    .set(Address::getIsDefault, AddressStatus.NORMAL_ADDRESS)
                    .update();
        }
        address = BeanUtil.copyProperties(addressDTO, Address.class);
        address.setId(addressId);
        try {
            updateById(address);
        } catch (Exception e) {
            log.error("修改收货地址失败，userId={}，addressId={}", userId, addressId, e);
            throw e;
        }
        log.info("用户 {} 修改收货地址 {} 成功", userId, addressId);
    }


    /**
     * 校验地址是否存在
     *
     * @param addressId 地址ID
     * @return 是否存在
     */
    @Override
    public boolean existsById(Long addressId) {
        return lambdaQuery().eq(Address::getId, addressId).exists();
    }
}
