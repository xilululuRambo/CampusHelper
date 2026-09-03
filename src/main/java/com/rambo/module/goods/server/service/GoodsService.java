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
     * 主动失效商品详情缓存（供绕过本类方法的商品状态变更路径调用，如订单工作流、超时关单 Job）
     * @param id 商品ID
     */
    void evictGoodsDetail(Long id);
}
