package com.macro.mall.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.domain.MewHelpChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MewHelp HTTP 适配器，会员 ID 始终取自商城已认证的登录会话。
 */
@Component
public class MewHelpClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MewHelpClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration responseTimeout;

    public MewHelpClient(ObjectMapper objectMapper,
                         @Value("${mewhelp.base-url:http://localhost:8000}") String baseUrl,
                         @Value("${mewhelp.connect-timeout:3s}") Duration connectTimeout,
                         @Value("${mewhelp.response-timeout:120s}") Duration responseTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.responseTimeout = responseTimeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * 转发 MewHelp 的 SSE 数据流，不缓存完整客服回复。
     */
    public ResponseEntity<StreamingResponseBody> streamChat(String memberId, MewHelpChatRequest request) {
        try {
            Long conversationId = request.getConversationId();
            if (conversationId != null && !ownsConversation(memberId, conversationId)) {
                return sseResponse(HttpStatus.NOT_FOUND, "会话不存在");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", memberId);
            payload.put("message", request.getMessage());
            payload.put("conversation_id", conversationId);
            HttpRequest upstreamRequest = HttpRequest.newBuilder(uri("/api/chat"))
                    .timeout(responseTimeout)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                LOGGER.warn("MewHelp chat returned HTTP {}: {}",
                        response.statusCode(), summarizeUpstreamError(errorBody));
                return unavailableSseResponse();
            }

            StreamingResponseBody stream = output -> {
                try (InputStream input = response.body()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        output.flush();
                    }
                }
            };
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl("no-cache, no-transform");
            headers.set("X-Accel-Buffering", "no");
            return new ResponseEntity<>(stream, headers, HttpStatus.OK);
        } catch (MewHelpUnavailableException e) {
            return unavailableSseResponse();
        } catch (IOException e) {
            LOGGER.warn("MewHelp chat request failed: {}", e.getMessage());
            return unavailableSseResponse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("MewHelp chat request was interrupted");
            return unavailableSseResponse();
        }
    }

    /**
     * 获取当前登录会员在 MewHelp 中的会话列表。
     */
    public JsonNode listConversations(String memberId) {
        String encodedMemberId = URLEncoder.encode(memberId, StandardCharsets.UTF_8);
        return getJson("/api/conversations?user_id=" + encodedMemberId);
    }

    /**
     * 读取历史消息前先校验会话归属。
     */
    public JsonNode listMessages(String memberId, long conversationId) {
        if (!ownsConversation(memberId, conversationId)) {
            return null;
        }
        return getJson("/api/conversations/" + conversationId + "/messages");
    }

    private boolean ownsConversation(String memberId, long conversationId) {
        JsonNode conversations = listConversations(memberId).path("items");
        if (!conversations.isArray()) {
            throw new MewHelpUnavailableException();
        }
        for (JsonNode conversation : conversations) {
            if (conversation.path("id").asLong(-1) == conversationId) {
                return true;
            }
        }
        return false;
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(path))
                    .timeout(responseTimeout)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("MewHelp request returned HTTP {}", response.statusCode());
                throw new MewHelpUnavailableException();
            }
            return objectMapper.readTree(response.body());
        } catch (MewHelpUnavailableException e) {
            throw e;
        } catch (IOException e) {
            LOGGER.warn("MewHelp JSON request failed: {}", e.getMessage());
            throw new MewHelpUnavailableException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MewHelpUnavailableException();
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private String summarizeUpstreamError(String body) {
        try {
            JsonNode error = objectMapper.readTree(body);
            JsonNode details = error.path("detail");
            if (details.isArray()) {
                StringBuilder summary = new StringBuilder();
                for (JsonNode detail : details) {
                    if (summary.length() > 0) summary.append("; ");
                    summary.append("type=").append(detail.path("type").asText("unknown"))
                            .append(", location=").append(detail.path("loc"))
                            .append(", message=").append(detail.path("msg").asText(""));
                }
                return summary.toString();
            }
            return details.isMissingNode() ? "empty error response" : details.asText(details.toString());
        } catch (IOException e) {
            return "unreadable error response";
        }
    }

    private ResponseEntity<StreamingResponseBody> unavailableSseResponse() {
        return sseResponse(HttpStatus.SERVICE_UNAVAILABLE, "智能客服暂时不可用，请稍后重试");
    }

    private ResponseEntity<StreamingResponseBody> sseResponse(HttpStatus status, String message) {
        String frame = "event: error\ndata: {\"message\":\"" + message + "\"}\n\ndata: [DONE]\n\n";
        StreamingResponseBody stream = output -> {
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.setCacheControl("no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        return new ResponseEntity<>(stream, headers, status);
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The upstream response is already invalid; closing it must not replace the result.
        }
    }

    public static class MewHelpUnavailableException extends RuntimeException {
    }
}
