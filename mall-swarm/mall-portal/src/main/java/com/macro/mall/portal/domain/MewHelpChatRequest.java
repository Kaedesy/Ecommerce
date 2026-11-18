package com.macro.mall.portal.domain;

/**
 * 商城向 MewHelp 发起一轮客服对话时使用的请求。
 */
public class MewHelpChatRequest {
    private String message;
    private Long conversationId;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }
}