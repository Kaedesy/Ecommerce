package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** 秒杀申请参数；会员身份只从服务端登录态解析。 */
public class FlashOrderSubmitParam {
    @Schema(description = "秒杀商品关联编号")
    private Long promotionRelationId;
    @Schema(description = "秒杀数量，当前版本固定为 1")
    private Integer quantity;
    @Schema(description = "会员收货地址编号")
    private Long receiveAddressId;
    public Long getPromotionRelationId() { return promotionRelationId; }
    public void setPromotionRelationId(Long promotionRelationId) { this.promotionRelationId = promotionRelationId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Long getReceiveAddressId() { return receiveAddressId; }
    public void setReceiveAddressId(Long receiveAddressId) { this.receiveAddressId = receiveAddressId; }
}