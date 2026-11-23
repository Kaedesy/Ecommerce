package com.macro.mall.portal.service.impl;

import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.mapper.UmsMemberReceiveAddressMapper;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** 秒杀消息消费者的数据库事务：最终配额校验、限购校验和建单。 */
@Service
public class FlashOrderCreateService {
    private final FlashPromotionRequestDao requestDao;
    private final JdbcTemplate jdbcTemplate;
    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsOrderSettingMapper orderSettingMapper;
    private final UmsMemberReceiveAddressMapper addressMapper;
    private final UmsMemberService memberService;
    private final OmsPortalOrderService orderService;

    public FlashOrderCreateService(FlashPromotionRequestDao requestDao, JdbcTemplate jdbcTemplate,
                                  OmsOrderMapper orderMapper, OmsOrderItemMapper orderItemMapper,
                                  OmsOrderSettingMapper orderSettingMapper,
                                  UmsMemberReceiveAddressMapper addressMapper,
                                  UmsMemberService memberService, OmsPortalOrderService orderService) {
        this.requestDao = requestDao;
        this.jdbcTemplate = jdbcTemplate;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderSettingMapper = orderSettingMapper;
        this.addressMapper = addressMapper;
        this.memberService = memberService;
        this.orderService = orderService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createOrder(String requestNo) {
        FlashPromotionRequest request = requestDao.selectForUpdate(requestNo);
        if (request == null || "ORDER_CREATED".equals(request.getStatus()) || "RESTORED".equals(request.getStatus())) return;
        // The publisher sends while the request is still PUBLISHING. The
        // consumer can receive the message before the publisher records the
        // broker confirm and changes the status to PUBLISHED.
        if (!("PUBLISHING".equals(request.getStatus()) || "PUBLISHED".equals(request.getStatus())
                || "PROCESSING".equals(request.getStatus()))) return;
        requestDao.markProcessing(requestNo);

        Map<String, Object> relation;
        Map<String, Object> product;
        UmsMember member = memberService.getById(request.getMemberId());
        UmsMemberReceiveAddress address = addressMapper.selectByPrimaryKey(request.getReceiveAddressId());
        try {
            relation = jdbcTemplate.queryForMap("SELECT id, product_id, product_sku_id, flash_promotion_price, "
                    + "flash_promotion_count, flash_promotion_limit FROM sms_flash_promotion_product_relation "
                    + "WHERE id=? AND stock_status='ACTIVE'", request.getPromotionRelationId());
            product = jdbcTemplate.queryForMap("SELECT p.id product_id, p.name product_name, p.pic product_pic, "
                    + "p.product_sn product_sn, p.product_category_id category_id, p.gift_point gift_point, "
                    + "p.gift_growth gift_growth, b.name brand_name, s.sku_code sku_code, s.sp_data sp_data "
                    + "FROM pms_product p JOIN pms_sku_stock s ON s.product_id=p.id "
                    + "LEFT JOIN pms_brand b ON b.id=p.brand_id WHERE p.id=? AND s.id=? "
                    + "AND p.delete_status=0 AND p.publish_status=1", relation.get("product_id"), request.getProductSkuId());
        } catch (Exception exception) {
            requestDao.markRestorePending(requestNo, "秒杀活动、商品或 SKU 已不可用");
            return;
        }
        if (member == null || address == null || !request.getMemberId().equals(address.getMemberId())) {
            requestDao.markRestorePending(requestNo, "会员或收货地址已失效");
            return;
        }
        int reserved = jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation "
                        + "SET reserved_count=reserved_count+? WHERE id=? AND product_sku_id=? AND stock_status='ACTIVE' "
                        + "AND reserved_count+sold_count+?<=flash_promotion_count",
                request.getQuantity(), request.getPromotionRelationId(), request.getProductSkuId(), request.getQuantity());
        if (reserved != 1) {
            requestDao.markRestorePending(requestNo, "活动配额已不足或活动已结束");
            return;
        }
        jdbcTemplate.update("INSERT IGNORE INTO sms_flash_promotion_user_limit "
                + "(promotion_relation_id, member_id, reserved_count, bought_count) VALUES (?, ?, 0, 0)",
                request.getPromotionRelationId(), request.getMemberId());
        int limited = jdbcTemplate.update("UPDATE sms_flash_promotion_user_limit "
                        + "SET reserved_count=reserved_count+? WHERE promotion_relation_id=? AND member_id=? "
                        + "AND reserved_count+bought_count+?<=?",
                request.getQuantity(), request.getPromotionRelationId(), request.getMemberId(),
                request.getQuantity(), relation.get("flash_promotion_limit"));
        if (limited != 1) {
            jdbcTemplate.update("UPDATE sms_flash_promotion_product_relation SET reserved_count=reserved_count-? WHERE id=? AND reserved_count>=?",
                    request.getQuantity(), request.getPromotionRelationId(), request.getQuantity());
            requestDao.markRestorePending(requestNo, "会员已达到活动限购数量");
            return;
        }

        BigDecimal price = (BigDecimal) relation.get("flash_promotion_price");
        BigDecimal amount = price.multiply(BigDecimal.valueOf(request.getQuantity()));
        OmsOrder order = new OmsOrder();
        order.setMemberId(member.getId());
        order.setMemberUsername(member.getUsername());
        order.setOrderSn("F" + UUID.randomUUID().toString().replace("-", ""));
        order.setCreateTime(new Date());
        order.setTotalAmount(amount);
        order.setPayAmount(amount);
        order.setFreightAmount(BigDecimal.ZERO);
        order.setPromotionAmount(BigDecimal.ZERO);
        order.setIntegrationAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayType(0);
        order.setSourceType(1);
        order.setStatus(0);
        order.setOrderType(1);
        order.setPromotionInfo("秒杀订单");
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhoneNumber());
        order.setReceiverPostCode(address.getPostCode());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());
        order.setConfirmStatus(0);
        order.setDeleteStatus(0);
        order.setIntegration(0);
        order.setGrowth(0);
        OmsOrderSetting setting = orderSettingMapper.selectByPrimaryKey(1L);
        if (setting != null) order.setAutoConfirmDay(setting.getConfirmOvertime());
        orderMapper.insert(order);

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setProductId(((Number) product.get("product_id")).longValue());
        item.setProductName((String) product.get("product_name"));
        item.setProductPic((String) product.get("product_pic"));
        item.setProductBrand((String) product.get("brand_name"));
        item.setProductSn((String) product.get("product_sn"));
        item.setProductPrice(price);
        item.setProductQuantity(request.getQuantity());
        item.setProductSkuId(request.getProductSkuId());
        item.setProductSkuCode((String) product.get("sku_code"));
        item.setProductCategoryId(product.get("category_id") == null ? null : ((Number) product.get("category_id")).longValue());
        item.setPromotionName("秒杀活动");
        item.setPromotionAmount(BigDecimal.ZERO);
        item.setCouponAmount(BigDecimal.ZERO);
        item.setIntegrationAmount(BigDecimal.ZERO);
        item.setRealAmount(amount);
        item.setGiftIntegration(0);
        item.setGiftGrowth(0);
        item.setProductAttr((String) product.get("sp_data"));
        orderItemMapper.insert(item);
        if (requestDao.markOrderCreated(requestNo, order.getId()) != 1) Asserts.fail("秒杀请求状态迁移失败");
        orderService.sendDelayMessageCancelOrder(order.getId());
    }
}
