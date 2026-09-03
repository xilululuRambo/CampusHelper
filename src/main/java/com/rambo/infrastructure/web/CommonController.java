package com.rambo.infrastructure.web;

import cn.hutool.core.util.IdUtil;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.RedisKeyConstants;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.common.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/common")
@Tag(name = "公共接口")
@Slf4j
public  class CommonController {

    @Resource
    private CacheClient cacheClient;

    /**
     * 获取防重复提交令牌
     * @param scene 场景，用于区分不同的提交场景
     * @return 令牌
     */
    @GetMapping("/submit-token")
    public Result<String> getSubmitToken(@RequestParam("scene") String scene) {
        log.info("获取防重复提交令牌，场景：{}", scene);
        Long userId = IdHolder.getId();
        String token = IdUtil.simpleUUID();
        String key = RedisKeyConstants.SUBMIT_TOKEN_PREFIX + scene + ":" + userId + ":" + token;
        cacheClient.set(key, "1", NumConstants.SUBMIT_TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return Result.success(token);
    }
}