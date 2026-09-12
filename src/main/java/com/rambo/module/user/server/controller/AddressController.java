package com.rambo.module.user.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.annotation.PreventDuplicate;
import com.rambo.common.result.Result;
import com.rambo.module.user.pojo.dto.AddressDTO;
import com.rambo.module.user.pojo.vo.AddressVO;
import com.rambo.module.user.server.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/address")
@Validated
@Tag(name = "地址接口", description = "用户地址相关接口")
public class AddressController {
    @Resource
    private AddressService addressService;

    /**
     * 保存用户地址
     *
     * @param addressDTO 地址请求参数DTO
     * @return 成功结果VO
     */
    @PostMapping
    @NoAuthAnnotation
    //TODO@PreventDuplicate(scene = "saveAddress")
    @Operation(summary = "保存地址")
    public Result<Void> saveAddress(@Valid @RequestBody AddressDTO addressDTO) {
        log.info("保存地址请求参数: {}", addressDTO);
        addressService.saveAddress(addressDTO);
        return Result.success();
    }

    /**
     * 获取用户地址列表
     *
     * @return 用户地址列表
     */
    @NoAuthAnnotation
    @GetMapping("/list")
    @Operation(summary = "获取用户地址列表")
    public Result<List<AddressVO>> getAddressList() {
        log.info("获取用户地址列表");
        List<AddressVO> addressList = addressService.getAddressList();
        return Result.success(addressList);
    }

    /**
     * 更新用户地址
     *
     * @param addressId  地址ID
     * @param addressDTO 地址请求参数DTO
     * @return 成功结果VO
     */
    @NoAuthAnnotation
    @PutMapping("/{addressId}")
    @Operation(summary = "更新地址")
    public Result<Void> updateAddress(@NotNull(message = "地址ID不能为空") @PathVariable("addressId") Long addressId,
                                    @Valid @RequestBody AddressDTO addressDTO) {
        log.info("更新地址请求参数: {}, {}", addressId, addressDTO);
        addressService.updateAddress(addressId,addressDTO);
        return Result.success();
    }

    /**
     * 删除用户地址
     *
     * @param addressId 地址ID
     * @return 成功结果VO
     */
    @NoAuthAnnotation
    @DeleteMapping("/{addressId}")
    @Operation(summary = "删除地址")
    public Result<Void> deleteAddress(@NotNull(message = "地址ID不能为空") @PathVariable("addressId") Long addressId) {
        log.info("删除地址请求参数: {}", addressId);
        addressService.deleteAddress(addressId);
        return Result.success();
    }
}
