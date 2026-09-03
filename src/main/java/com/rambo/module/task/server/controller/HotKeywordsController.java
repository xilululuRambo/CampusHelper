package com.rambo.module.task.server.controller;

import com.rambo.common.result.Result;
import com.rambo.module.task.pojo.vo.HotKeywordsVO;
import com.rambo.module.task.server.service.HotKeywordsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/task/hot/keywords")
@Slf4j
@Validated
@Tag(name = "热门关键词接口")
public class HotKeywordsController {
    @Resource
    private HotKeywordsService hotKeywordsService;

    /**
     * 获取热门关键词
     * @return 热门关键词列表
     */
    @GetMapping
    public Result<List<HotKeywordsVO>> getHotKeywords() {
        log.info("获取热门关键词");
        List<HotKeywordsVO> hotKeywordsList = hotKeywordsService.getHotKeywords();
        return Result.success(hotKeywordsList);
    }
}
