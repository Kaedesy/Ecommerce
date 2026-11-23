package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.FlashOrderSubmitParam;
import com.macro.mall.portal.service.impl.FlashPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "秒杀下单")
@RequestMapping("/flash")
public class FlashPromotionController {
    private final FlashPromotionService flashPromotionService;
    public FlashPromotionController(FlashPromotionService flashPromotionService) { this.flashPromotionService = flashPromotionService; }

    @Operation(summary = "查询秒杀商品信息")
    @GetMapping("/detail/{relationId}")
    public CommonResult<Map<String, Object>> detail(@PathVariable Long relationId) {
        return CommonResult.success(flashPromotionService.getDetail(relationId));
    }

    @Operation(summary = "提交秒杀请求")
    @PostMapping("/order")
    public CommonResult<Map<String, Object>> submit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @RequestBody FlashOrderSubmitParam param) {
        return CommonResult.success(flashPromotionService.submit(idempotencyKey, param));
    }

    @Operation(summary = "查询秒杀请求状态")
    @GetMapping("/order/{requestNo}")
    public CommonResult<Map<String, Object>> status(@PathVariable String requestNo) {
        return CommonResult.success(flashPromotionService.getStatus(requestNo));
    }
}