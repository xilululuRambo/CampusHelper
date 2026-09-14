package com.rambo.module.goods.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.search.AbstractEsOutboxSyncService;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.server.mapper.GoodsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品 ES 索引同步组件（业务层编排：登记 Outbox 意图 → 派发时回源商品 → EsUtil 写入）。
 *
 * <p>同步逻辑收敛到基础设施层基类 {@link AbstractEsOutboxSyncService}，本类只负责
 * 「商品领域」的三件事：数据类型标识、回源加载商品、以及商品索引的读写原语。
 * 由于回源用 {@link GoodsMapper}（而非 GoodsService），避免了与 GoodsServiceImpl 的循环依赖。</p>
 *
 * <p>ES external version 由发件箱行 id 担任——基类 {@link AbstractEsOutboxSyncService#dispatch}
 * 把 row.id 作为 {@code version} 传下来，本类再透传给 {@link EsUtil#saveGoods}。</p>
 */
@Component
@Slf4j
public class GoodsEsSyncService extends AbstractEsOutboxSyncService {

    @Resource
    private GoodsMapper goodsMapper;

    @Resource
    private EsUtil esUtil;

    @Resource
    private AliyunOssUtil aliyunOssUtil;

    @Override
    protected String dataType() {
        return PrefixConstants.GOODS_TYPE;
    }

    @Override
    protected EsDTO loadEsDTO(Long dataId) {
        // 逻辑删除的商品 selectById 返回 null（@TableLogic 自动过滤），此处即「已删除」信号
        Goods goods = goodsMapper.selectById(dataId);
        if (goods == null) {
            return null;
        }
        EsDTO esDTO = BeanUtil.copyProperties(goods, EsDTO.class);
        // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
        esDTO.setStatus(goods.getStatus() != null ? goods.getStatus().getCode() : null);
        return esDTO;
    }

    @Override
    protected void saveEsDoc(EsDTO esDTO, long version) throws Exception {
        esUtil.saveGoods(esDTO, version);
    }

    @Override
    protected void deleteEsDoc(Long dataId, long version) throws Exception {
        esUtil.deleteGoods(dataId, version);
    }

    /**
     * 覆写外部文件清理：ES 同步达成后删除随行登记的商品图片（旧图/删除图）。
     * <p>OSS 删除失败会抛 {@code BusinessException}，由基类捕获并交由 Outbox 重试。</p>
     */
    @Override
    protected void deleteExternalFiles(List<String> fileUrls) throws Exception {
        if (fileUrls == null || fileUrls.isEmpty()) {
            return;
        }
        aliyunOssUtil.deleteFiles(fileUrls);
    }

    /**
     * 登记商品「写入/更新」意图（发布场景无待清理文件）。
     *
     * @param goods 商品实体类
     */
    public void syncToEsAsync(Goods goods) {
        enqueueUpsert(goods.getId(), null);
    }

    /**
     * 登记商品「写入/更新」意图（只持有商品 ID 的调用方使用，避免为了拿到实体多查一次库）。
     *
     * <p>发件箱只记录 {@code dataId}，派发时由 {@link #loadEsDTO(Long)} 回源加载最新商品，
     * 因此调用方无需提供实体快照。适用于「状态由 CAS 直改、当前线程未持有实体」的流转场景。</p>
     *
     * @param goodsId 商品ID
     */
    public void syncToEsAsync(Long goodsId) {
        enqueueUpsert(goodsId, null);
    }

    /**
     * 登记商品「写入/更新」意图，并随行登记被替换的旧图（ES 同步达成后清理 OSS）。
     *
     * @param goods          商品实体类
     * @param obsoleteImages 本次更新后被替换的旧图（逗号分隔），无则为 null
     */
    public void updateGoodsEsAsync(Goods goods, String obsoleteImages) {
        enqueueUpsert(goods.getId(), obsoleteImages);
    }

    /**
     * 登记商品「删除」意图，并随行登记商品图片（ES 删除达成后清理 OSS）。
     */
    public void deleteGoodsFromEsAsync(Long goodsId, String images) {
        enqueueDelete(goodsId, images);
    }
}
