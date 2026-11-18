# MewHelp 智能客服接入

`mall-portal` 通过服务端适配器调用 MewHelp。商城网关地址前缀为 `/mall-portal`，网关会先验证会员 Sa-Token；后端从当前登录会话读取会员 ID，并将其作为 MewHelp 的 `user_id`，客户端不能指定其他会员 ID。

## 配置

`mall-portal` 默认连接 `http://localhost:8000`，适用于本机启动 MewHelp 和商城服务的开发环境。可通过环境变量调整：

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `MEWHELP_BASE_URL` | `http://localhost:8000` | MewHelp 服务地址，不带末尾斜杠也可 |
| `MEWHELP_CONNECT_TIMEOUT` | `3s` | 建立连接的超时 |
| `MEWHELP_RESPONSE_TIMEOUT` | `120s` | 等待客服响应的超时 |

商城服务运行在 Docker 中时，`localhost` 指向商城容器本身；应把 `MEWHELP_BASE_URL` 配成 MewHelp 容器的服务名地址，或 Docker Desktop 可访问的宿主机地址。Kubernetes 部署时配置为 MewHelp 的 Service 地址。

## 接口

所有接口要求商城会员登录。请求经过网关时使用商城现有 `Authorization: Bearer <token>` 请求头。

### 流式发送消息

`POST /mall-portal/customer-service/chat`

```json
{
  "message": "我的订单什么时候发货？",
  "conversationId": null
}
```

首次对话把 `conversationId` 设为 `null`；MewHelp 会在 SSE 的 `done` 事件中返回新会话 ID。后续请求传入该 ID。接口透传 MewHelp `/api/chat` 的 SSE 数据帧，包括 `delta`、`citations`、`actions`、`interrupt`、`done`，最后以 `data: [DONE]` 结束。前端应使用支持 POST 请求体的流式读取方式处理响应。

### 查询会话和历史消息

- `GET /mall-portal/customer-service/conversations`
- `GET /mall-portal/customer-service/conversations/{conversationId}/messages`

响应遵循商城 `CommonResult` 格式。历史消息查询会先核对会话是否属于当前会员，再转发给 MewHelp。

## 前端调用要点

商城前端项目不在当前 `mall-swarm` 仓库中。前端客服组件需要将登录 token 放入 `Authorization` 请求头，保存 SSE `done` 事件返回的 `conversation_id`，并在下一轮请求中以 `conversationId` 传回。断开连接或刷新页面后，可先读取会话列表，再加载所选会话的历史消息。