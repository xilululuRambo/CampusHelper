package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.enumType.RetryStatus;
import com.rambo.infrastructure.retry.RetryRecorder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EsSyncRetryServiceImpl extends ServiceImpl<EsSyncRetryMapper, EsSyncRetry> implements EsSyncRetryService, RetryRecorder {

    /**
     * 重试次数阈值
     */
    private static final int MAX_RETRY_COUNT = 5;


    /**
     * 添加重试记录
     *
     * <p>同 dataId + dataType 已有记录（含 SUCCESS/FAILED 终态）时复用该记录并重置
     * 重试次数与状态：上次补偿失败终止后，同一条数据再次同步失败必须重新进入待重试队列，
     * 否则记录停留在 FAILED 永远无法再被补偿（与消息重试表"失败后可重新开始"语义一致）。</p>
     *
     * @param dataId   业务数据ID 商品ID/任务ID
     * @param dataType 数据类型 goods商品 task任务
     * @param fileUrls OSS文件地址，多个用逗号分隔
     * @param errorMsg 错误信息
     */
    @Override
    public void addRetry(Long dataId, String dataType, String fileUrls, String errorMsg) {
        // 检查是否存在相同数据ID的重试记录
        EsSyncRetry esSyncRetry = lambdaQuery()
                .eq(EsSyncRetry::getDataId, dataId)
                .eq(EsSyncRetry::getDataType, dataType).one();

        if (esSyncRetry != null) {
            // 已存在（含终态）：重新进入待重试，重置次数与状态
            esSyncRetry.setErrorMsg(errorMsg);
            esSyncRetry.setFileUrls(fileUrls);
            esSyncRetry.setStatus(RetryStatus.PENDING);
            esSyncRetry.setRetryCount(0);
            updateById(esSyncRetry);
            return;
        }
        // 如果不存在，创建新的重试记录（显式初始化状态与次数，不依赖数据库默认值）
        esSyncRetry = new EsSyncRetry();
        esSyncRetry.setDataId(dataId);
        esSyncRetry.setDataType(dataType);
        esSyncRetry.setFileUrls(fileUrls);
        esSyncRetry.setErrorMsg(errorMsg);
        esSyncRetry.setStatus(RetryStatus.PENDING);
        esSyncRetry.setRetryCount(0);
        save(esSyncRetry);
    }

    /**
     * 查询待重试数据
     *
     * @return 待重试数据列表
     */
    @Override
    public List<EsSyncRetry> getWaitRetryList() {
        return lambdaQuery()
                .eq(EsSyncRetry::getStatus, RetryStatus.PENDING)
                .list();
    }

    /**
     * 按数据类型查询待重试数据
     */
    @Override
    public List<EsSyncRetry> getWaitRetryList(String dataType) {
        return lambdaQuery()
                .eq(EsSyncRetry::getDataType, dataType)
                .eq(EsSyncRetry::getStatus, RetryStatus.PENDING)
                .list();
    }

    /**
     * 标记重试成功
     *
     * @param id 主键ID
     */
    @Override
    public void markSuccess(Long id) {
        lambdaUpdate()
                .eq(EsSyncRetry::getId, id)
                .set(EsSyncRetry::getStatus, RetryStatus.SUCCESS)
                .update();
    }


    /**
     * 增加重试次数
     *
     * @param id       主键ID
     * @param errorMsg 错误信息
     */
    @Override
    public void incrRetryCount(Long id, String errorMsg) {
        // 增加重试次数
        lambdaUpdate()
                .eq(EsSyncRetry::getId, id)
                .eq(EsSyncRetry::getStatus, RetryStatus.PENDING)
                .setSql("retry_count = retry_count + 1") // 直接+1，不用查
                .set(EsSyncRetry::getErrorMsg, errorMsg)
                .update();

        // 超过5次改成失败终止
        lambdaUpdate()
                .eq(EsSyncRetry::getId, id)
                .ge(EsSyncRetry::getRetryCount, MAX_RETRY_COUNT)
                .set(EsSyncRetry::getStatus, RetryStatus.FAILED)
                .update();
    }
}
