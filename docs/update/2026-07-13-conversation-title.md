# 对话标题生成与会话管理 — 前端对接说明

## 变更概述

1. **WebSocket 新增 `TITLE_UPDATE` 消息** — 首次对话时异步推送标题
2. **新增 REST 接口** — 查询当前用户的所有会话列表

---

## 一、WebSocket：TITLE_UPDATE 消息

### 触发时机

新对话（即服务端数据库中不存在该 `conversationId` 的记录）的首条 `CHAT` 消息发送后，服务端异步调用 LLM 生成标题，通过 `TITLE_UPDATE` 消息推送。

### 消息格式

```json
{
  "type": "TITLE_UPDATE",
  "data": {
    "title": "文件查找方法",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `String` | LLM 自动生成的标题（≤20 字），生成失败时默认值为 "新对话" |
| `conversationId` | `String` | 对应 SESSION_ACK 返回的会话 ID，用于客户端关联到正确的对话 |

### 时序说明

```
客户端                                                  服务端
  |                                                       |
  |--- START_SESSION ---------------->|                   |
  |<--- SESSION_ACK {sessionId} ------|                   |
  |                                                       |
  |--- CHAT {modelId, content} ------>|  ← 首次 CHAT 触发标题生成
  |<--- TITLE_UPDATE {title, conversationId} |  ← 异步推送（可能在 TOOL_CALL_START / TEXT 之前或之后到达）
  |<--- TEXT {content} ---------------|                   |
  |                                                       |
  |--- CHAT {modelId, content} ------>|  ← 后续 CHAT 不再触发标题生成
```

> **注意**: `TITLE_UPDATE` 是异步生成的，可能在 LLM 回复流（`TOOL_CALL_START`/`TEXT`/`DONE`）之前、之中或之后到达，客户端不应依赖其到达顺序。建议客户端在收到后更新对话列表中的标题显示。

### 判断新/旧对话的逻辑

- 客户端发送 `START_SESSION` 时若传入 `sessionId`，服务端通过 `AiConversationRepo.existsByConversationId(sessionId)` 判断是否为新对话
- 若 `sessionId` 已存在 → 视为重连旧对话，不生成标题
- 若 `sessionId` 不存在（或由服务端生成 UUID） → 视为新对话，下次 `CHAT` 时生成标题

---

## 二、REST 接口：会话列表

### 获取会话列表

```
GET /api/ai/conversation/list
```

**权限**：需登录

**响应示例**：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "uid": 1,
      "createAt": "2026-07-13T12:00:00",
      "updateAt": "2026-07-13T12:30:00",
      "conversationId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "文件查找方法"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 数据库主键 |
| `uid` | `Long` | 所属用户 ID |
| `createAt` | `Date` | 创建时间 |
| `updateAt` | `Date` | 最后更新时间（按此字段降序排列） |
| `conversationId` | `String` | 会话 ID，用于 WebSocket START_SESSION 传入 |
| `title` | `String` | 对话标题 |

> 当前未分页，返回该用户全部会话记录。

---

## 三、前端适配建议

1. **建立 WebSocket 连接后**：收到 `SESSION_ACK` 时记录 `sessionId`
2. **收到 `TITLE_UPDATE`**：根据 `data.conversationId` 在对话列表中找到对应会话，更新其 `title` 显示
3. **打开会话列表页**：调用 `GET /api/ai/conversation/list` 渲染列表
4. **重连场景**：打开已有会话时，`START_SESSION` 传入历史 `sessionId`，服务端不会再生成 Title
