package com.macro.mall.dao;

import com.macro.mall.model.SmsFlashPromotionProductRelation;
import org.apache.ibatis.annotations.Param;

/** 秒杀活动商品及其物理库存操作。 */
public interface FlashPromotionRelationDao {
    int reserveSkuStock(@Param("skuId") Long skuId, @Param("productId") Long productId, @Param("promotionId") Long promotionId, @Param("sessionId") Long sessionId, @Param("quantity") Integer quantity);
    int releaseSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
    int claimForDelete(@Param("id") Long id);
    int insertRelation(SmsFlashPromotionProductRelation relation);
    SmsFlashPromotionProductRelation selectRelation(@Param("id") Long id);
    int deletePreparing(@Param("id") Long id);
}