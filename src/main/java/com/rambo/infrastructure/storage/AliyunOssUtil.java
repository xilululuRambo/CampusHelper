package com.rambo.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片上传工具类（封装OSS上传 + 图片校验）
 * 只负责上传并返回URL
 */
@Slf4j
@Component
public class AliyunOssUtil {

    /**
     * 签名 URL 有效期：30 分钟
     */
    private static final long URL_EXPIRE_MS = 30 * 60 * 1000L;

    /**
     * 安全缓冲：URL 剩余有效期不足 5 分钟视为过期，
     * 避免客户端拿到后因网络/加载耗时恰好撞上过期点
     */
    private static final long URL_SAFE_MARGIN_MS = 5 * 60 * 1000L;

    /**
     * URL 短缓存条数上限，超出时先清理已过期条目，防止长期运行内存泄漏
     */
    private static final int MAX_URL_CACHE_SIZE = 10000;

    /**
     * URL 短缓存：objectName -> 签名URL + URL绝对过期时间。
     * 同一 objectName 在 URL 有效期内复用同一签名，保证 URL 稳定；
     * 命中条件校验 URL 绝对过期时间（双保险），即使配置漂移也不会返回过期 URL
     */
    private record CachedUrl(String url, long expireAt) {}

    private final Map<String, CachedUrl> urlCache = new ConcurrentHashMap<>();

    @Resource
    private AliyunOssProperties aliOssProperties;

    @Resource
    private OSS ossClient;

    /**
     * 上传文件到OSS，返回可访问的URL
     *
     * @param file 上传的文件
     * @return 文件URL
     */
    public String upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // 1. 生成唯一文件名（避免重复）
        String objectName = UUID.randomUUID() + suffix;

        // 2. 创建OSS客户端并上传
        try {
            ossClient.putObject(aliOssProperties.getBucketName(), objectName, file.getInputStream());
            log.info("上传成功，文件名：{}", objectName);
            return objectName;
        } catch (IOException e) {
            log.error("OSS 上传失败，objectName：{}", objectName, e);
            throw new BusinessException(MessageConstants.FILE_UPLOAD_FAILED, e);
        }
    }

    /**
     * 生成可访问的URL，过期时间为30分钟。
     * 同一 objectName 在 URL 有效期内复用同一签名结果（25分钟内URL稳定），
     * 过期后自动用同一 objectName 重新签名。
     *
     * @param objectName 文件名
     * @return 文件URL
     */
    public String getUrl(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedUrl cached = urlCache.get(objectName);
        // 双保险：命中且 URL 剩余有效期 > 5 分钟缓冲，否则重新签名
        if (cached != null && cached.expireAt() - URL_SAFE_MARGIN_MS > now) {
            return cached.url();
        }
        Date expire = new Date(now + URL_EXPIRE_MS);
        String url = ossClient.generatePresignedUrl(aliOssProperties.getBucketName(), objectName, expire).toString();
        putCachedUrl(objectName, new CachedUrl(url, now + URL_EXPIRE_MS));
        return url;
    }

    /**
     * 批量生成可访问的URL（自动去重，复用短缓存）
     *
     * @param objectNames 文件名集合
     * @return 文件URL列表
     */
    public List<String> getUrls(Collection<String> objectNames) {
        if (objectNames == null || objectNames.isEmpty()) {
            return Collections.emptyList();
        }
        return objectNames.stream().map(this::getUrl).toList();
    }

    /**
     * 写入URL短缓存，超出容量时先清理过期条目
     */
    private void putCachedUrl(String objectName, CachedUrl cachedUrl) {
        if (urlCache.size() >= MAX_URL_CACHE_SIZE) {
            long now = System.currentTimeMillis();
            urlCache.entrySet().removeIf(e -> e.getValue().expireAt() - URL_SAFE_MARGIN_MS <= now);
        }
        urlCache.put(objectName, cachedUrl);
    }

    /**
     * 删除文件
     *
     * @param objectName 文件名
     */
    public void deleteFile(String objectName) {
        if (objectName == null || objectName.isEmpty()) {
            log.info("文件名为空,不删除oss文件");
            return;
        }
        // 从OSS删除文件
        try {
            ossClient.deleteObject(aliOssProperties.getBucketName(), objectName);
            log.info("删除成功，文件名：{}", objectName);
        } catch (Exception e) {
            log.error("OSS 删除失败，objectName：{}", objectName, e);
            throw new BusinessException(MessageConstants.FILE_DELETE_FAILED, e);
        }
    }

    /**
     * 批量上传文件（List形式），直接返回文件名列表
     */
    public List<String> uploadFilesAndGetNames(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException(MessageConstants.FILE_EMPTY);
        }
        return Arrays.stream(files).map(this::upload).toList();
    }

    /**
     * 批量删除文件
     */
    public void deleteFiles(List<String> objectNames) {
        if (objectNames == null || objectNames.isEmpty()) {
            return;
        }
        for (String objectName : objectNames) {
            deleteFile(objectName);
        }
    }
}