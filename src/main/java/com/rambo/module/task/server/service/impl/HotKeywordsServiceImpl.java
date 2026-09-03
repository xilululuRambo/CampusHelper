package com.rambo.module.task.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.task.pojo.entity.HotKeywords;
import com.rambo.module.task.pojo.vo.HotKeywordsVO;
import com.rambo.module.task.server.mapper.HotKeywordsMapper;
import com.rambo.module.task.server.service.HotKeywordsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class HotKeywordsServiceImpl extends ServiceImpl<HotKeywordsMapper, HotKeywords> implements HotKeywordsService {
    @Resource
    private CacheClient cacheClient;

    /**
     * 获取热门关键词
     *
     * @return 热门关键词列表
     */
    @Override
    public List<HotKeywordsVO> getHotKeywords() {
        //redis中查询热门关键词（返回有序 List，保持热搜 Top10 顺序）
        List<CacheClient.ZSetEntry> hotKeywords = cacheClient.zReverseRangeWithScores(PrefixConstants.HOT_KEYWORDS, 0, NumConstants.TOP_RANK_END_INDEX);

        //校验结果是否为空
        if (!hotKeywords.isEmpty()) {
            return hotKeywords.stream()
                    .map(entry -> {
                        HotKeywordsVO hotKeywordsVO = new HotKeywordsVO();
                        hotKeywordsVO.setKeyword(entry.value());
                        hotKeywordsVO.setSearchCount((int) entry.score());
                        return hotKeywordsVO;
                    })
                    .toList();
        }

        return List.of();
    }
}
