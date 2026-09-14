package com.rambo.module.goods.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.chat.enums.MessageType;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.common.enumType.SortDirectionEnum;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.database.TransactionUtils;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.module.chat.server.service.ChatService;
import com.rambo.module.chat.server.service.ChatSessionService;
import com.rambo.module.goods.pojo.dto.GoodsDTO;
import com.rambo.module.goods.pojo.dto.GoodsOrderDTO;
import com.rambo.module.goods.pojo.dto.GoodsQueryDTO;
import com.rambo.module.goods.pojo.dto.OrderItemDTO;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.vo.GoodsDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsListVO;
import com.rambo.module.goods.server.mapper.GoodsMapper;
import com.rambo.module.goods.server.service.GoodsCategoryService;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.rambo.module.goods.server.service.OrderItemService;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.infrastructure.cache.LockClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

    @Resource
    private AliyunOssUtil ossUtil;

    @Resource
    private GoodsEsSearchService goodsEsSearchService;

    @Resource
    private GoodsEsSyncService goodsEsSyncService;

    @Resource
    private GoodsOrderService orderService;

    @Resource
    private LockClient lockClient;

    @Resource
    private GoodsCategoryService goodsCategoryService;

    @Resource
    private OrderItemService orderItemService;

    @Resource
    private NotificationSender notificationSender;

    @Resource
    private ChatSessionService chatSessionService;

    @Resource
    private ChatService chatService;

    /**
     * 商品发布
     *
     * @param goodsDTO 商品发布DTO
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "null",
            action = OperationActionEnum.GOODS_PUBLISH, descriptionEL = "'发布商品'")
    public void publishGoods(GoodsDTO goodsDTO, MultipartFile[] imagesFiles) {
        // 校验商品图片
        validateGoodsImages(imagesFiles);

        // 校验商品分类是否存在
        boolean isExist = goodsCategoryService.checkCategoryExist(goodsDTO.getCategoryId());
        if (!isExist) {
            throw new BusinessException(MessageConstants.GOODS_CATEGORY_NOT_FOUND);
        }

        // 处理商品图片上传返回图片URL列表
        List<String> imageNames = ossUtil.uploadFilesAndGetNames(imagesFiles);
        String imagesStr = String.join(",", imageNames);

        // 保存商品到数据库
        Goods goods = BeanUtil.copyProperties(goodsDTO, Goods.class);
        goods.setImages(imagesStr);
        goods.setOwnerId(IdHolder.getId());
        try {
            save(goods);
        } catch (Exception e) {
            // DB 保存失败：删除已上传的图片，避免孤儿文件
            ossUtil.deleteFiles(imageNames);
            throw e;
        }

        // 异步同步 ES（失败不影响业务）
        goodsEsSyncService.syncToEsAsync(goods);
    }

    /**
     * 商品更新
     *
     * @param goodsDTO 商品更新DTO
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_UPDATE, descriptionEL = "'更新商品 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public void updateGoods(Long id, GoodsDTO goodsDTO, MultipartFile[] imagesFiles) {


        // 校验商品是否存在并检查用户是否有权限更新商品
        Goods goods = checkGoods(id);

        // 校验商品分类是否存在
        boolean isExist = goodsCategoryService.checkCategoryExist(goodsDTO.getCategoryId());
        if (!isExist) {
            throw new BusinessException(MessageConstants.GOODS_CATEGORY_NOT_FOUND);
        }

        List<String> newImageNames = null;

        String oldImages = goods.getImages();

        if (imagesFiles != null && imagesFiles.length > 0) {
            // 校验商品图片
            validateGoodsImages(imagesFiles);

            // 上传新图片
            newImageNames = ossUtil.uploadFilesAndGetNames(imagesFiles);
            goods.setImages(String.join(",", newImageNames));
        }

        // 处理商品信息更新
        BeanUtil.copyProperties(goodsDTO, goods);

        boolean isSuccess = updateById(goods);

        if (!isSuccess) {
            if (newImageNames != null) {
                ossUtil.deleteFiles(newImageNames);
            }
            throw new BusinessException(MessageConstants.SYSTEM_BUSY);
        }

        // 旧图清理随 ES 同步并入事务性 Outbox：ES 同步达成后删除旧图，失败则整体重试
        // 仅当本次上传了新图（旧图被替换）时才需清理旧图；未换图时 images 未变，不可删
        String obsoleteImages = (newImageNames != null && StringUtils.hasText(oldImages)) ? oldImages : null;
        goodsEsSyncService.updateGoodsEsAsync(goods, obsoleteImages);
    }

    /**
     * 商品状态更新
     *
     * @param id          商品ID
     * @param goodsStatus 商品状态
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_STATUS_UPDATE, descriptionEL = "'更新商品状态 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public void updateGoodsStatus(Long id, GoodsStatus goodsStatus) {
        // 校验商品是否存在并检查用户是否有权限更新商品
        Goods goods = checkGoods(id);

        //校验目标商品状态是否允许编辑
        if(!goodsStatus.equals(GoodsStatus.NORMAL) && !goodsStatus.equals(GoodsStatus.DISABLED)) {
            throw new BusinessException(MessageConstants.GOODS_STATUS_IS_INVALID);
        }

        // 管理员强制下架的商品，商家不可自行修改状态（含恢复上架）
        if (Boolean.TRUE.equals(goods.getAdminDisabled())) {
            throw new BusinessException(MessageConstants.GOODS_DISABLED_BY_ADMIN);
        }

        goods.setStatus(goodsStatus);

        // 更新商品状态
        boolean isSuccess = updateById(goods);
        if (!isSuccess) {
            throw new BusinessException(MessageConstants.SYSTEM_BUSY);
        }

        // 商品状态已变更（NORMAL <-> DISABLED）：事务内登记 ES 同步意图，与业务写库同事务原子落库。
        // 补齐背景：ES 同步原先只覆盖"发布/编辑/删除"，一切"流转导致的状态变更"均被遗漏，
        // 表现为商品已下架但搜索页仍能搜到、点进去才被状态校验拒绝。
        goodsEsSyncService.syncToEsAsync(goods);
    }

    /**
     * 管理员强制下架/恢复商品（跳过发布者身份校验，保留存在性/状态/乐观锁校验）
     * 管理员拥有最高处置权：除已售出终态外，在售/交易中/用户已下架的商品均可强制下架；
     * 交易中下架会通知未完成订单的购买者；恢复在售需清管理员标记
     *
     * @param id          商品ID
     * @param goodsStatus 目标状态（管理员操作：NORMAL-恢复在售 / DISABLED-强制下架）
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_STATUS_UPDATE_BY_ADMIN, descriptionEL = "'管理员更新商品状态 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public void updateGoodsStatusByAdmin(Long id, GoodsStatus goodsStatus) {
        // 校验商品是否存在
        Goods goods = getById(id);
        if (goods == null) {
            throw new BusinessException(MessageConstants.GOODS_NOT_FOUND);
        }

        // 校验目标状态只允许在售/下架
        if (!goodsStatus.equals(GoodsStatus.NORMAL) && !goodsStatus.equals(GoodsStatus.DISABLED)) {
            throw new BusinessException(MessageConstants.GOODS_STATUS_IS_INVALID);
        }

        if (goodsStatus.equals(GoodsStatus.DISABLED)) {
            // 终态（已售出）不可下架；已是管理员下架视为重复操作
            // 交易中（有未完成订单）/用户已自行下架的商品均可强制下架——管理员拥有最高处置权
            if (goods.getStatus() == GoodsStatus.SOLD_OUT
                    || (goods.getStatus() == GoodsStatus.DISABLED && Boolean.TRUE.equals(goods.getAdminDisabled()))) {
                throw new BusinessException(MessageConstants.GOODS_STATUS_IS_INVALID);
            }
            goods.setStatus(GoodsStatus.DISABLED);
            goods.setAdminDisabled(true);
        } else {
            // 恢复在售，清除管理员下架标记
            goods.setStatus(GoodsStatus.NORMAL);
            goods.setAdminDisabled(false);
        }

        // 乐观锁更新是否成功
        boolean isSuccess = updateById(goods);
        if (!isSuccess) {
            throw new BusinessException(MessageConstants.SYSTEM_BUSY);
        }

        // 强制下架：通知发布者（未完成订单的取消与买家通知由管理端编排层联动 cancelOrderByAdmin 处理）
        if (goodsStatus.equals(GoodsStatus.DISABLED)) {
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(goods.getOwnerId())
                    .type(NotificationType.GOODS_DISABLED)
                    .content(EnumConstants.ADMIN_NOTIFICATION_TYPE_GOODS_OFF_SHELF)
                    .refId(id)
                    .build());
        }

        // 商品状态已变更（NORMAL <-> DISABLED）：事务内登记 ES 同步意图。
        // 管理员下架的商品必须立即从搜索结果中消失，否则用户搜到后点进详情立刻被状态校验拒绝。
        goodsEsSyncService.syncToEsAsync(goods);
    }

    /**
     * 商品详情
     *
     * @param id 商品ID
     * @return 商品详情VO
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_VIEW, descriptionEL = "'查看商品详情(公开) id=' + #id")
    @Cacheable(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public GoodsDetailVO getGoods(Long id) {
        log.info("查看商品详情 id={}", id);
        // 校验商品是否存在
        Goods goods = getById(id);
        if (goods == null) {
            throw new BusinessException(MessageConstants.GOODS_NOT_FOUND);
        }
        GoodsDetailVO goodsDetailVO = BeanUtil.copyProperties(goods, GoodsDetailVO.class);
        List<String> images = new ArrayList<>();

        // 图片列表为 OSS objectName，响应出口由 OssUrlResponseBodyAdvice 统一签名
        if (StringUtils.hasText(goods.getImages())) {
            images.addAll(Arrays.asList(goods.getImages().split(",")));
        }
        goodsDetailVO.setImages(images);
        return goodsDetailVO;
    }

    /**
     * 订单域状态流转统一出口：交易中 → 已售出（买家确认收货）。
     *
     * <p>订单工作流与超时关单 Job 出于 CAS 并发条件需要直改商品状态，统一经本方法流转：
     * 「DB 状态变更 + 详情缓存失效」在同一处收敛（{@code @CacheEvict}），不再依赖各写路径
     * 人工逐个补失效——人工补漏的路径（如用户取消订单）曾导致详情缓存残留「交易中」旧状态。</p>
     *
     * @param goodsId 商品ID
     * @return 是否实际完成流转（false=商品已不在交易中，调用方据此跳过后续联动）
     */
    @Override
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#goodsId")
    public boolean markSoldOutIfTrading(Long goodsId) {
        return lambdaUpdate()
                .eq(Goods::getId, goodsId)
                .eq(Goods::getStatus, GoodsStatus.TRADING)
                .set(Goods::getStatus, GoodsStatus.SOLD_OUT)
                .update();
    }

    /**
     * 订单域状态流转统一出口：交易中 → 在售（用户取消订单 / 超时关单恢复）。
     *
     * <p>管理员强制下架的商品状态为 DISABLED，CAS 条件匹配不到则跳过恢复，不阻塞订单取消；
     * 详情缓存失效与状态变更同处收敛，避免缓存残留「交易中」。</p>
     *
     * @param goodsId 商品ID
     * @return 是否实际完成流转（false=商品已不在交易中，调用方据此跳过后续联动）
     */
    @Override
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#goodsId")
    public boolean restoreToNormalIfTrading(Long goodsId) {
        return lambdaUpdate()
                .eq(Goods::getId, goodsId)
                .eq(Goods::getStatus, GoodsStatus.TRADING)
                .set(Goods::getStatus, GoodsStatus.NORMAL)
                .update();
    }

    /**
     * 商品删除
     *
     * @param id 商品ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_DELETE, descriptionEL = "'删除商品 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public void deleteGoods(Long id) {
        // 校验商品是否存在并检查用户是否有权限删除商品
        Goods goods = checkGoods(id);

        // 删除商品信息（图片清理随 ES 同步并入 Outbox：ES 删除达成后清理 OSS）
        removeById(id);

        // 异步同步 ES（失败不影响业务），随行登记待清理的商品图片
        goodsEsSyncService.deleteGoodsFromEsAsync(id, goods.getImages());
    }

    /**
     * 商品列表
     *
     * @return 商品列表VO列表
     */
    @Override
    public PageResult<GoodsListVO> getGoodsList(GoodsQueryDTO goodsQueryDTO) {
        long pageNum = goodsQueryDTO.getPageNum();
        long pageSize = goodsQueryDTO.getPageSize();

        // 关键词存在：ES 完成 关键词匹配 + 价格过滤 + 排序 + 分页，数据库仅回查实体
        if (StringUtils.hasText(goodsQueryDTO.getKeyword())) {
            try {
                PageResult<Long> esPage = goodsEsSearchService.searchGoodsPage(goodsQueryDTO.getKeyword(),
                        goodsQueryDTO.getMinPrice(), goodsQueryDTO.getMaxPrice(),
                        goodsQueryDTO.getSortDirection(), pageNum, pageSize);

                // 无匹配结果
                if (esPage.getTotal() == 0) {
                    return new PageResult<>(0, Collections.emptyList());
                }

                // 按 ES 返回的 ID 回查，并保持 ES 排序顺序（in 查询无序）
                List<Goods> goodsList = lambdaQuery()
                        .in(Goods::getId, esPage.getRecords())
                        .eq(Goods::getStatus, GoodsStatus.NORMAL)
                        .list();
                Map<Long, Goods> goodsMap = goodsList.stream()
                        .collect(Collectors.toMap(Goods::getId, g -> g));
                List<Goods> ordered = esPage.getRecords().stream()
                        .map(goodsMap::get)
                        .filter(Objects::nonNull)
                        .toList();

                return getGoodsVOPageResult(ordered, esPage.getTotal());
            } catch (IOException e) {
                // es查询失败，降级数据库 LIKE 查询
                log.error("es查询失败", e);
            }
        }

        // 无关键词 或 ES 降级：数据库分页查询
        var query = lambdaQuery();

        // 降级时数据库 LIKE 查询商品标题
        if (StringUtils.hasText(goodsQueryDTO.getKeyword())) {
            query.like(Goods::getTitle, goodsQueryDTO.getKeyword());
        }
        // 分页构造器
        Page<Goods> page = new Page<>(pageNum, pageSize);

        boolean isAsc = SortDirectionEnum.ASC.equals(goodsQueryDTO.getSortDirection());

        query.eq(Goods::getStatus, GoodsStatus.NORMAL)
                .ge(goodsQueryDTO.getMinPrice() != null, Goods::getPrice, goodsQueryDTO.getMinPrice())
                .le(goodsQueryDTO.getMaxPrice() != null, Goods::getPrice, goodsQueryDTO.getMaxPrice());

        if (goodsQueryDTO.getSortDirection() != null) {
            // 用户指定了价格排序方向
            query.orderBy(true, isAsc, Goods::getPrice);
        } else {
            // 默认按创建时间降序
            query.orderByDesc(Goods::getCreateTime);
        }
        Page<Goods> goodsList = query.page(page);

        // 处理商品列表VO
        return getGoodsVOPageResult(goodsList);
    }

    /**
     * 我的发布的商品
     *
     * @param goodsStatus 商品状态
     * @return 我的发布的商品列表VO列表
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.GOODS_VIEW_MINE, descriptionEL = "'查看我发布的商品'")
    public PageResult<GoodsListVO> getMyGoodsList(GoodsStatus goodsStatus, PageQuery pageQueryDTO) {
        log.info("用户 {} 查看我发布的商品列表", IdHolder.getId());
        // 分页构造器
        Page<Goods> page = new Page<>(pageQueryDTO.getPageNum(), pageQueryDTO.getPageSize());

        Page<Goods> goodsList = lambdaQuery()
                .eq(Goods::getOwnerId, IdHolder.getId())
                .eq(goodsStatus != null, Goods::getStatus, goodsStatus)
                .orderByDesc(Goods::getCreateTime)
                .page(page);

        // 处理商品列表VO
        return getGoodsVOPageResult(goodsList);
    }

    /**
     * 购买商品
     *
     * @param id 商品ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.GOODS,
            targetIdEL = "#id",
            action = OperationActionEnum.GOODS_BUY, descriptionEL = "'购买商品 id=' + #id")
    @CacheEvict(cacheNames = CacheConstants.GOODS_DETAIL, key = "#id")
    public void buyGoods(Long id) {
        String lockKey = PrefixConstants.GOODS_BUY_LOCK_PREFIX + id;
        boolean locked = false;
        try {
            if (!lockClient.tryLock(lockKey, NumConstants.LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            // 校验商品是否存在
            Goods goods = getById(id);
            if (goods == null) {
                throw new BusinessException(MessageConstants.GOODS_NOT_FOUND);
            }

            //商品是否是自己的商品
            if (goods.getOwnerId().equals(IdHolder.getId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            // 校验商品状态是否允许购买
            if (goods.getStatus() != GoodsStatus.NORMAL) {
                throw new BusinessException(MessageConstants.GOODS_STATUS_IS_INVALID);
            }

            // 更新商品状态为交易中
            goods.setStatus(GoodsStatus.TRADING);
            boolean isSuccess = updateById(goods);
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.GOODS_BUYED);
            }

            // 商品状态已变更（NORMAL -> TRADING）：事务内登记 ES 同步意图。
            // 交易中的商品必须立即从搜索结果中消失，否则第二个买家搜到后点击购买会被 GOODS_STATUS_IS_INVALID 拒绝
            goodsEsSyncService.syncToEsAsync(goods);

            // 下单订单
            GoodsOrderDTO goodsOrderDTO = new GoodsOrderDTO();
            goodsOrderDTO.setGoodsId(id);
            goodsOrderDTO.setOwnerId(goods.getOwnerId());
            goodsOrderDTO.setBuyerId(IdHolder.getId());
            goodsOrderDTO.setTotalAmount(goods.getPrice());
            // 创建订单并返回订单ID
            Long orderId = orderService.createOrder(goodsOrderDTO);


            //快照订单商品
            OrderItemDTO orderItemDTO = new OrderItemDTO();
            orderItemDTO.setOrderId(orderId);
            orderItemDTO.setGoodsId(id);
            orderItemDTO.setGoodsTitle(goods.getTitle());
            orderItemDTO.setDescription(goods.getDescription());
            orderItemDTO.setImages(goods.getImages());
            orderItemDTO.setPrice(goods.getPrice());
            orderItemService.createOrderItem(orderItemDTO);

            // 通知
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(goods.getOwnerId())
                    .type(NotificationType.GOODS_ORDER)
                    .content(MessageConstants.GOODS_ORDERED)
                    .refId(orderId)
                    .build());

            //创建会话 + 自动消息（事务提交后才写入 MongoDB，回滚不残留假会话）
            String sessionId = "goods_" + orderId;
            Long buyerId = IdHolder.getId();
            ChatMessage autoMsg = new ChatMessage();
            autoMsg.setSessionId(sessionId);
            autoMsg.setSenderId(goods.getOwnerId());  // 卖家作为发送者
            autoMsg.setReceiverId(buyerId);
            autoMsg.setContent(MessageConstants.GOODS_ORDER_AGREE_SESSION);
            autoMsg.setMsgType(MessageType.TEXT);  // 文本
            autoMsg.setCreateTime(LocalDateTime.now());
            autoMsg.setIsRead(IsReadStatus.UNREAD);  // 未读
            TransactionUtils.afterCommit(() -> {
                chatSessionService.createIfNotExist(sessionId, goods.getOwnerId(), buyerId);
                chatService.saveMessage(autoMsg);
            });

        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 校验商品是否存在并检查用户是否有权限更新商品
     *
     * @param id 商品ID
     * @return 商品实体类
     */
    private Goods checkGoods(Long id) {
        // 校验商品是否存在
        Goods goods = getById(id);
        if (goods == null) {
            throw new BusinessException(MessageConstants.GOODS_NOT_FOUND);
        }

        // 校验用户是否有权限更新商品
        if (!goods.getOwnerId().equals(IdHolder.getId())) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 校验当前商品状态是否允许编辑
        if (!goods.getStatus().equals(GoodsStatus.NORMAL) && !goods.getStatus().equals(GoodsStatus.DISABLED)) {
            throw new BusinessException(MessageConstants.GOODS_STATUS_IS_INVALID);
        }
        return goods;
    }

    /**
     * 商品列表VO转换
     *
     * @param goodsList 商品分页结果
     * @return 商品列表VO列表页结果
     */
    private PageResult<GoodsListVO> getGoodsVOPageResult(Page<Goods> goodsList) {
        return getGoodsVOPageResult(goodsList.getRecords(), goodsList.getTotal());
    }

    /**
     * 商品列表VO转换（支持 ES 分页路径：传入有序记录 + ES 精确 total）
     */
    private PageResult<GoodsListVO> getGoodsVOPageResult(List<Goods> records, long total) {
        // 校验商品列表是否为空
        if (records.isEmpty()) {
            return new PageResult<>(total, Collections.emptyList());
        }

        List<GoodsListVO> goodsList = new ArrayList<>();
        for (Goods goods : records) {
            GoodsListVO goodsListVO = BeanUtil.copyProperties(goods, GoodsListVO.class);

            // 封面图为 OSS objectName，响应出口由 OssUrlResponseBodyAdvice 统一签名
            if (StringUtils.hasText(goods.getImages())) {
                String[] imageNames = goods.getImages().split(",");
                goodsListVO.setImages(imageNames[0]);
                goodsList.add(goodsListVO);
            }
        }
        return new PageResult<>(total, goodsList);
    }

    /**
     * 校验商品图片
     *
     * @param files 商品图片文件数组
     */
    private void validateGoodsImages(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException(MessageConstants.GOODS_IMAGE_EMPTY);
        }
        if (files.length > NumConstants.GOODS_IMAGE_MAX_COUNT) {
            throw new BusinessException(MessageConstants.GOODS_IMAGE_MAX_COUNT);
        }
        for (MultipartFile file : files) {
            // 大小
            if (file.getSize() > NumConstants.GOODS_IMAGE_MAX_SIZE) {
                throw new BusinessException(MessageConstants.GOODS_IMAGE_SIZE_ERROR);
            }
            // 格式
            String original = file.getOriginalFilename();
            String suffix = null;
            if (original != null) {
                suffix = original.substring(original.lastIndexOf(".")).toLowerCase();
            }
            if (suffix == null) {
                throw new BusinessException(MessageConstants.GOODS_IMAGE_FORMAT_INVALID);
            }
            if (!NumConstants.GOODS_IMAGE_ALLOWED_TYPES.contains(suffix)) {
                throw new BusinessException(MessageConstants.GOODS_IMAGE_FORMAT_INVALID);
            }
        }
    }
}
