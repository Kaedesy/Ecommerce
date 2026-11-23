package com.macro.mall.portal.dao;

import com.macro.mall.portal.domain.FlashPromotionRequest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FlashPromotionRequestDao {
    int insertInit(FlashPromotionRequest request);
    java.util.List<FlashPromotionRequest> selectInitRequests(@Param("limit") Integer limit);
    FlashPromotionRequest selectByMemberKey(@Param("memberId") Long memberId, @Param("idempotencyKey") String idempotencyKey);
    FlashPromotionRequest selectByRequestNo(@Param("memberId") Long memberId, @Param("requestNo") String requestNo);
    FlashPromotionRequest selectForUpdate(@Param("requestNo") String requestNo);
    FlashPromotionRequest selectByOrderId(@Param("orderId") Long orderId);
    java.util.List<FlashPromotionRequest> selectRestorePending(@Param("limit") Integer limit);
    int markProcessing(@Param("requestNo") String requestNo);
    int markOrderCreated(@Param("requestNo") String requestNo, @Param("orderId") Long orderId);
    int markPaid(@Param("requestNo") String requestNo);
    int markRestored(@Param("requestNo") String requestNo);
    int markClosedAfterEnd(@Param("requestNo") String requestNo);
    int resetExpiredClaims();
    int markPublishRetry(@Param("requestNo") String requestNo);
    int markRestorePending(@Param("requestNo") String requestNo, @Param("reason") String reason);
    int markReady(@Param("requestNo") String requestNo);
    int markRejected(@Param("requestNo") String requestNo, @Param("reason") String reason);
    List<String> selectReadyRequestNos(@Param("limit") Integer limit);
    int claimForPublish(@Param("requestNo") String requestNo);
    int markPublished(@Param("requestNo") String requestNo);
}