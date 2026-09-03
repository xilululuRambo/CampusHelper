package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface EsSyncRetryService extends IService<EsSyncRetry> {
    /**
     * 添加重试记录
     */
    void addRetry(Long dataId, String dataType,String fileUrls,String errorMsg);

    /**
     * 获取待重试列表
     */
    List<EsSyncRetry> getWaitRetryList();

    /**
     * 获取指定数据类型的待重试列表（供各业务模块的定时任务只处理自己的类型）
     *
     * @param dataType 数据类型：goods / task
     */
    List<EsSyncRetry> getWaitRetryList(String dataType);

    /**
     * 标记成功
     */
    void markSuccess(Long id);

    /**
     * 重试次数+1，超限则标记终止
     */
    void incrRetryCount(Long id, String errorMsg);
}
