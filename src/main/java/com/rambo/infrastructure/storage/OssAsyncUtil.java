package com.rambo.infrastructure.storage;

import com.rambo.infrastructure.retry.RetryRecorder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class OssAsyncUtil {
    @Resource
    private AliyunOssUtil aliyunOssUtil;

    @Resource
    private RetryRecorder retryRecorder;

    /**
     * 异步删除OSS文件（失败自动记录重试表）
     */
    public void deleteFileAsync(String objectName, String bizType, Long bizId,String fileUrl) {
        CompletableFuture.runAsync(() -> {
            try {
                aliyunOssUtil.deleteFile(objectName);
                log.info("异步删除OSS成功：{}", objectName);
            } catch (Exception e) {
                log.error("异步删除OSS失败：{}", objectName, e);
                // 失败 → 进入重试表
                retryRecorder.addRetry(bizId, bizType, fileUrl, e.getMessage());
            }
        });
    }


    /**
     * 异步批量删除OSS文件（你要的批量版）
     */
    public void deleteFilesAsync(List<String> objectNames, String bizType, Long bizId) {
        CompletableFuture.runAsync(() -> {
            try {
                aliyunOssUtil.deleteFiles(objectNames);
                log.info("异步批量删除OSS成功，数量：{}", objectNames.size());
            } catch (Exception e) {
                log.error("异步批量删除OSS失败，id：{}", bizId, e);
                // 失败 → 进入重试表
                String fileUrls = String.join(", ", objectNames);
                retryRecorder.addRetry(bizId, bizType, fileUrls, e.getMessage());
            }
        });
    }
}
