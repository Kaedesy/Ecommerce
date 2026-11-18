package com.macro.mall.portal.controller;

import cn.dev33.satoken.exception.NotLoginException;
import com.fasterxml.jackson.databind.JsonNode;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.MewHelpChatRequest;
import com.macro.mall.portal.service.MewHelpClient;
import com.macro.mall.portal.util.StpMemberUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 商城会员客服接口；会员身份从 Sa-Token 登录会话读取，不接受客户端传入的 user_id。
 */
@RestController
@Tag(name = "MewHelpController", description = "MewHelp 智能客服")
@RequestMapping("/customer-service")
public class MewHelpController {
    private final MewHelpClient mewHelpClient;

    public MewHelpController(MewHelpClient mewHelpClient) {
        this.mewHelpClient = mewHelpClient;
    }

    /**
     * 向 MewHelp 发送一条消息并返回 SSE 对话流。
     */
    @Operation(summary = "发送消息并返回 SSE 对话流")
    @PostMapping("/chat")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody MewHelpChatRequest request) {
        String memberId = currentMemberId();
        if (request.getMessage() == null || request.getMessage().isBlank()
                || request.getMessage().length() > 8000
                || (request.getConversationId() != null && request.getConversationId() <= 0)) {
            return errorStream(HttpStatus.BAD_REQUEST, "消息不能为空且长度不能超过 8000 个字符");
        }
        return mewHelpClient.streamChat(memberId, request);
    }

    /**
     * 查询当前登录会员的客服会话列表。
     */
    @Operation(summary = "查询当前会员的客服会话列表")
    @GetMapping("/conversations")
    public ResponseEntity<CommonResult<JsonNode>> conversations() {
        try {
            return ResponseEntity.ok(CommonResult.success(
                    mewHelpClient.listConversations(currentMemberId())));
        } catch (MewHelpClient.MewHelpUnavailableException e) {
            return unavailable();
        }
    }

    /**
     * 查询当前会员指定客服会话的历史消息。
     */
    @Operation(summary = "查询当前会员客服会话的历史消息")
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<CommonResult<JsonNode>> messages(@PathVariable long conversationId) {
        if (conversationId <= 0) {
            return ResponseEntity.badRequest().body(CommonResult.<JsonNode>validateFailed("会话编号无效"));
        }
        try {
            JsonNode messages = mewHelpClient.listMessages(currentMemberId(), conversationId);
            if (messages == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(CommonResult.<JsonNode>failed("会话不存在"));
            }
            return ResponseEntity.ok(CommonResult.success(messages));
        } catch (MewHelpClient.MewHelpUnavailableException e) {
            return unavailable();
        }
    }

    /**
     * 未经登录的直连请求也返回标准未登录响应。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<CommonResult<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResult.<Void>unauthorized(null));
    }

    private String currentMemberId() {
        StpMemberUtil.getStpLogic().checkLogin();
        return String.valueOf(StpMemberUtil.getStpLogic().getLoginId());
    }

    private ResponseEntity<StreamingResponseBody> errorStream(HttpStatus status, String message) {
        StreamingResponseBody stream = output -> {
            String frame = "event: error\n" + "data: {\"message\":\"" + message + "\"}\n\n" + "data: [DONE]\n\n";
            output.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(stream);
    }

    private ResponseEntity<CommonResult<JsonNode>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(CommonResult.<JsonNode>failed("智能客服暂时不可用，请稍后重试"));
    }
}
