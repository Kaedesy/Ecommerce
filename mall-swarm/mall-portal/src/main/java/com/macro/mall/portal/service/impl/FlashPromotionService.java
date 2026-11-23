package com.macro.mall.portal.service.impl;

import com.macro.mall.common.exception.Asserts;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberReceiveAddress;
import com.macro.mall.portal.dao.FlashPromotionRequestDao;
import com.macro.mall.portal.domain.FlashOrderSubmitParam;
import com.macro.mall.portal.domain.FlashPromotionRequest;
import com.macro.mall.portal.service.UmsMemberReceiveAddressService;
import com.macro.mall.portal.service.UmsMemberService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 秒杀请求入口：身份校验、幂等持久化和 Redis 原子预扣减。 */
@Service
public class FlashPromotionService {
    private static final RedisScript<Long> RESERVE_SCRIPT = loadRedisScript("lua/flash_reserve.lua");
    private static final long REQUEST_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashPromotionService.class);

private static RedisScript<Long> loadRedisScript(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }
    private final UmsMemberService memberService;
    private final UmsMemberReceiveAddressService addressService;
    private final FlashPromotionRequestDao requestDao;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public FlashPromotionService(UmsMemberService memberService,
                                 UmsMemberReceiveAddressService addressService,
                                 FlashPromotionRequestDao requestDao,
                                 StringRedisTemplate redisTemplate,
                                 JdbcTemplate jdbcTemplate) {
        this.memberService = memberService;
        this.addressService = addressService;
        this.requestDao = requestDao;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

public Map<String, Object> getDetail(Long relationId) {
        Map<String, Object> detail = jdbcTemplate.queryForMap("SELECT r.id promotion_relation_id, r.product_id, r.product_sku_id, "
                + "r.flash_promotion_price, r.flash_promotion_count, r.flash_promotion_limit, r.stock_status, "
                + "p.name product_name, p.pic product_pic, s.sku_code, s.sp_data "
                + "FROM sms_flash_promotion_product_relation r "
                + "JOIN pms_product p ON p.id=r.product_id JOIN pms_sku_stock s ON s.id=r.product_sku_id "
                + "WHERE r.id=?", relationId);
        String redisStock = redisTemplate.opsForValue().get("flash:{" + relationId + "}:stock");
        detail.put("availableCount", "ACTIVE".equals(detail.get("stock_status")) && redisStock != null ? Integer.parseInt(redisStock) : 0);
        return detail;
    }
    public Map<String, Object> submit(String idempotencyKey, FlashOrderSubmitParam param) {
        UmsMember member = memberService.getCurrentMember();
        String key = normalizeKey(idempotencyKey);
        if (param == null || param.getPromotionRelationId() == null || param.getReceiveAddressId() == null
                || param.getQuantity() == null || param.getQuantity() != 1) {
            Asserts.fail("秒杀请求参数无效；当前每次限购数量为 1");
        }
        String requestHash = sha256(param.getPromotionRelationId() + ":1:" + param.getReceiveAddressId());
        FlashPromotionRequest request = requestDao.selectByMemberKey(member.getId(), key);
        if (request != null) {
            if (!requestHash.equals(request.getRequestHash())) {
                Asserts.fail("同一 Idempotency-Key 不能用于不同的秒杀请求");
            }
            if ("INIT".equals(request.getStatus())) {
                Integer savedLimit = jdbcTemplate.queryForObject("SELECT flash_promotion_limit FROM sms_flash_promotion_product_relation WHERE id=?", Integer.class, request.getPromotionRelationId());
                if (savedLimit == null) Asserts.fail("秒杀活动商品不存在");
                reserveAndAdvance(request, savedLimit);
            }
            return toResult(requestDao.selectByMemberKey(member.getId(), key));
        }

        UmsMemberReceiveAddress address = addressService.getItem(param.getReceiveAddressId());
        if (address == null || !member.getId().equals(address.getMemberId())) Asserts.fail("收货地址不存在或不属于当前会员");
        Map<String, Object> relation = findActiveRelation(param.getPromotionRelationId());
        Long skuId = ((Number) relation.get("product_sku_id")).longValue();
        Integer purchaseLimit = ((Number) relation.get("flash_promotion_limit")).intValue();
        request = new FlashPromotionRequest();
        request.setRequestNo(UUID.randomUUID().toString().replace("-", ""));
        request.setMemberId(member.getId());
        request.setPromotionRelationId(param.getPromotionRelationId());
        request.setProductSkuId(skuId);
        request.setReceiveAddressId(address.getId());
        request.setQuantity(1);
        request.setIdempotencyKey(key);
        request.setRequestHash(requestHash);
        try {
            requestDao.insertInit(request);
        } catch (DuplicateKeyException duplicate) {
            FlashPromotionRequest existing = requestDao.selectByMemberKey(member.getId(), key);
            if (existing == null || !requestHash.equals(existing.getRequestHash())) {
                Asserts.fail("幂等键冲突，请检查请求参数");
            }
            if ("INIT".equals(existing.getStatus())) reserveAndAdvance(existing, purchaseLimit);
            return toResult(requestDao.selectByMemberKey(member.getId(), key));
        }
        reserveAndAdvance(request, purchaseLimit);
        return toResult(requestDao.selectByMemberKey(member.getId(), key));
    }

/** INIT 表示 Redis 结果可能未知；用原 requestNo 重放 Lua，不能直接加库存。 */
    @Scheduled(fixedDelayString = "${mall.flash.init-recovery-poll-ms:2000}")
    public void recoverInitRequests() {
        for (FlashPromotionRequest request : requestDao.selectInitRequests(50)) {
            try {
                Integer limit = jdbcTemplate.queryForObject("SELECT flash_promotion_limit FROM sms_flash_promotion_product_relation WHERE id=?",
                        Integer.class, request.getPromotionRelationId());
                if (limit != null) reserveAndAdvance(request, limit);
            } catch (Exception exception) {
                LOGGER.warn("Could not recover INIT flash request {} yet", request.getRequestNo(), exception);
            }
        }
    }
    public Map<String, Object> getStatus(String requestNo) {
        UmsMember member = memberService.getCurrentMember();
        FlashPromotionRequest request = requestDao.selectByRequestNo(member.getId(), requestNo);
        if (request == null) Asserts.fail("秒杀请求不存在");
        return toResult(request);
    }

    private Map<String, Object> findActiveRelation(Long relationId) {
        try {
            return jdbcTemplate.queryForMap("SELECT r.id, r.product_sku_id, r.flash_promotion_limit "
                    + "FROM sms_flash_promotion_product_relation r "
                    + "JOIN sms_flash_promotion p ON p.id=r.flash_promotion_id "
                    + "JOIN sms_flash_promotion_session s ON s.id=r.flash_promotion_session_id "
                    + "WHERE r.id=? AND r.stock_status='ACTIVE' AND p.status=1 AND s.status=1 "
                    + "AND CURDATE() BETWEEN p.start_date AND p.end_date "
                    + "AND CURTIME() BETWEEN s.start_time AND s.end_time", relationId);
        } catch (Exception exception) {
            Asserts.fail("秒杀活动未开始、已结束或尚未完成库存准备");
            return Collections.emptyMap();
        }
    }

    private void reserveAndAdvance(FlashPromotionRequest request, Integer limit) {
        String relationId = request.getPromotionRelationId().toString();
        String hashTag = "{" + relationId + "}";
        Long result;
        try {
            result = redisTemplate.execute(RESERVE_SCRIPT, Arrays.asList(
                            "flash:" + hashTag + ":stock",
                            "flash:" + hashTag + ":users",
                            "flash:" + hashTag + ":status",
                            "flash:" + hashTag + ":request:" + request.getRequestNo()),
                    request.getRequestNo(), request.getMemberId().toString(), request.getQuantity().toString(),
                    limit.toString(), Long.toString(REQUEST_TOKEN_TTL_SECONDS));
        } catch (Exception exception) {
            // 留在 INIT，重试时用原 requestNo 重放 Lua，不能盲目加回可能已扣减的库存。
            throw new IllegalStateException("秒杀库存服务暂不可用，请使用相同幂等键查询或重试", exception);
        }
        if (result == null || result < 0) {
            String reason = result == null ? "库存服务无响应" : switch (result.intValue()) {
                case -1 -> "活动尚未开始或已结束";
                case -2 -> "秒杀库存不足";
                case -3 -> "已达到活动限购数量";
                default -> "秒杀请求被拒绝";
            };
            requestDao.markRejected(request.getRequestNo(), reason);
            return;
        }
        requestDao.markReady(request.getRequestNo());
    }

    private Map<String, Object> toResult(FlashPromotionRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestNo", request.getRequestNo());
        result.put("status", request.getStatus());
        result.put("orderId", request.getOrderId());
        result.put("failReason", request.getFailReason());
        return result;
    }

    private String normalizeKey(String key) {
        if (key == null || key.trim().isEmpty() || key.trim().length() > 128) Asserts.fail("Idempotency-Key 长度必须为 1 到 128");
        String value = key.trim();
        if (!value.matches("[!-~]{1,128}")) Asserts.fail("Idempotency-Key 只能使用可见 ASCII 字符");
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }
}