package com.macro.mall.portal.domain;

/** 秒杀请求持久化状态视图。 */
public class FlashPromotionRequest {
    private Long id;
    private String requestNo;
    private Long memberId;
    private Long promotionRelationId;
    private Long productSkuId;
    private Long receiveAddressId;
    private Integer quantity;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private Long orderId;
    private String failReason;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getPromotionRelationId() { return promotionRelationId; }
    public void setPromotionRelationId(Long promotionRelationId) { this.promotionRelationId = promotionRelationId; }
    public Long getProductSkuId() { return productSkuId; }
    public void setProductSkuId(Long productSkuId) { this.productSkuId = productSkuId; }
    public Long getReceiveAddressId() { return receiveAddressId; }
    public void setReceiveAddressId(Long receiveAddressId) { this.receiveAddressId = receiveAddressId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}