package com.rambo.infrastructure.retry;

/**
 * 重试记录器 —— 面向「重试记录」业务语义的中性接口。
 *
 * 供基础设施内部各组件（ES 同步、OSS 删除等）在操作失败时登记一条重试记录，
 * 由各业务域的调度任务（XxlJob handler）按 dataType 补偿执行。
 * 调用方只依赖记录语义，无需感知底层存储结构（当前实现为 t_es_sync_retry 表）。
 */
public interface RetryRecorder {

    /**
     * 添加一条重试记录（同 dataId + dataType 已存在则更新错误信息与文件地址）
     *
     * @param dataId   业务数据ID（商品ID/任务ID/OSS关联ID）
     * @param dataType 数据类型（goods / task / oss 等）
     * @param fileUrls 关联文件地址（逗号分隔）；OSS 删除失败场景为待删除文件
     * @param errorMsg 错误信息
     */
    void addRetry(Long dataId, String dataType, String fileUrls, String errorMsg);
}
