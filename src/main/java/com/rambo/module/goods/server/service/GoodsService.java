package com.rambo.module.goods.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.module.goods.pojo.dto.GoodsDTO;
import com.rambo.module.goods.pojo.dto.GoodsQueryDTO;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.vo.GoodsDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsListVO;
import org.springframework.web.multipart.MultipartFile;

public interface GoodsService extends IService<Goods> {
    /**
     * 商品发布
     * @param goodsDTO 商品发布DTO
     */
    void publishGoods(GoodsDTO goodsDTO, MultipartFile[] imagesFiles);

    /**
     * 商品更新
     * @param goodsDTO 商品更新DTO
     */
    void updateGoods(Long id, GoodsDTO goodsDTO, MultipartFile[] imagesFiles);

    /**
     * 商品状态更新
     * @param id 商品ID
     * @param goodsStatus 商品状态
     */
    void updateGoodsStatus(Long id, GoodsStatus goodsStatus);

    /**
     * 管理员强制下架/恢复商品（跳过发布者身份校验，保留存在性/状态/乐观锁校验）
     * @param id 商品ID
     * @param goodsStatus 目标状态
     */
    void updateGoodsStatusByAdmin(Long id, GoodsStatus goodsStatus);

    /**
     * 商品详情
     * @param id 商品ID
     * @return 商品详情VO
     */
    GoodsDetailVO getGoods(Long id);

    /**
     * 商品删除
     * @param id 商品ID
     */
    void deleteGoods(Long id);

    /**
     * 商品列表
     * @return 商品列表VO列表
     */
    PageResult<GoodsListVO> getGoodsList(GoodsQueryDTO goodsQueryDTO);

    /**
     * 我的发布的商品
     * @return 我的发布的商品列表VO列表
     */
    PageResult<GoodsListVO> getMyGoodsList(GoodsStatus goodsStatus, PageQuery pageQueryDTO);

    /**
     * 购买商品
     * @param id 商品ID
     */
    void buyGoods(Long id);

    /**
     * 订单域状态流转统一出口：交易中 → 已售出（买家确认收货）。
     * <p>订单工作流与超时关单 Job 出于 CAS 并发条件需要直改商品状态，统一经本方法流转：
     * 「DB 状态变更 + 详情缓存失效」在同一处收敛（{@code @CacheEvict}），
     * 不再依赖各写路径人工逐个补失效。裸写 lambdaUpdate/updateById 直改状态属违规操作。</p>
     * @param goodsId 商品ID
     * @return 是否实际完成流转（false=商品已不在交易中，调用方据此跳过后续联动）
     */
    boolean markSoldOutIfTrading(Long goodsId);

    /**
     * 订单域状态流转统一出口：交易中 → 在售（用户取消订单 / 超时关单恢复）。
     * <p>管理员强制下架的商品状态为 DISABLED，CAS 条件匹配不到则跳过恢复，不阻塞订单取消。</p>
     * @param goodsId 商品ID
     * @return 是否实际完成流转（false=商品已不在交易中，调用方据此跳过后续联动）
     */
    boolean restoreToNormalIfTrading(Long goodsId);
}
