package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.task.pojo.entity.HotKeywords;
import com.rambo.module.task.pojo.vo.HotKeywordsVO;

import java.util.List;

public interface HotKeywordsService extends IService<HotKeywords> {
    /**
     * 获取热门关键词
     * @return 热门关键词列表
     */
    List<HotKeywordsVO> getHotKeywords();
}
