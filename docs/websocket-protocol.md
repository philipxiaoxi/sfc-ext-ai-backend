# AI 聊天 WebSocket 消息协议

## 概述

AI 聊天功能通过 WebSocket 连接与后端实时通信。

- **端点**: `ws://<host>/api/ai/wschat`
- **消息格式**: 纯文本 JSON
- **序列化**: 使用 `com.xiaotao.saltedfishcloud.utils.MapperHolder`（Jackson ObjectMapper）

所有消息使用 UTF-8 编码。

---

## 连接鉴权

WebSocket 握手时复用已有的 HTTP Session 认证信息（Spring Security）。连接建立后可通过 `WebSocketSession.getPrincipal()` 获取当前用户。

---

## 消息结构

### 请求（客户端 → 服务端）

```json
{
  "type": "<UserMessageType>",
  "data": { ... }
}
```

`data` 字段内容取决于 `type`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `UserMessageType` 枚举 | 消息类型 |
| `data` | `Object` | 类型专属数据，按 type 转换为对应 Payload |

对应 Java 类：`com.sfc.ai.model.chat.message.UserRequest`

### 响应（服务端 → 客户端）

```json
{
  "type": "<LlmMessageType>",
  "data": { ... }
}
```

`data` 字段内容取决于 `type`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `LlmMessageType` 枚举 | 消息类型 |
| `data` | `Object` | 类型专属数据，按 type 转换为对应 Payload |

对应 Java 类：`com.sfc.ai.model.chat.message.LlmResponse`

---

## 消息类型

### 用户消息类型（`UserMessageType`）

| 枚举值 | 说明 | data Payload |
|--------|------|-------------|
| `START_SESSION` | 开启会话 | `StartSessionPayload` |
| `CHAT` | 发送聊天消息 | `ChatPayload` |
| `TOOL_ACK` | 工具调用确认 | 待定 |
| `STOP` | 停止响应 | 无 |

### 服务端消息类型（`LlmMessageType`）

| 枚举值 | 说明 | data Payload |
|--------|------|-------------|
| `SESSION_ACK` | 会话确认 | `SessionAckPayload` |
| `TEXT` | 普通文本消息 | `TextPayload` |
| `THINKING_START` | 模型开始思考 | 待定 |
| `THINKING_END` | 模型思考结束 | 待定 |
| `TOOL_CALL_START` | 工具调用开始 | `ToolCallStartPayload` |
| `TOOL_CALL_END` | 工具调用结束 | `ToolCallEndPayload` |
| `TOOL_CALL` | 工具调用（已废弃，由 TOOL_CALL_START + TOOL_CALL_END 替代） | `ToolCallPayload` |
| `TOOL_CALL_REQ` | 工具调用请求（需要用户确认） | 待定 |
| `DONE` | 响应结束 | `DonePayload` |
| `ERROR` | 错误消息 | `ErrorPayload` |

---

## Payload 类型

### ChatPayload

用于 `CHAT` 消息。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelId` | `Long` | 是 | 模型 ID（对应 `LlmModel.id`） |
| `content` | `String` | 是 | 聊天消息内容 |

```json
{
  "type": "CHAT",
  "data": {
    "modelId": 1,
    "content": "你好"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ChatPayload`

### StartSessionPayload

用于 `START_SESSION` 消息。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | `String` | 否 | 可选的会话 ID，不传则由服务端生成 UUID |

```json
{
  "type": "START_SESSION",
  "data": {
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

```json
{
  "type": "START_SESSION",
  "data": {}
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.StartSessionPayload`

### SessionAckPayload

用于 `SESSION_ACK` 消息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | `String` | 服务端确认的会话 ID |

```json
{
  "type": "SESSION_ACK",
  "data": {
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.SessionAckPayload`

### TextPayload

用于 `TEXT` 消息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `String` | 文本回复内容 |

```json
{
  "type": "TEXT",
  "data": {
    "content": "你好，我是xxx模型，有什么能帮助我吗？"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.TextPayload`

### ErrorPayload

用于 `ERROR` 消息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `message` | `String` | 错误描述 |

```json
{
  "type": "ERROR",
  "data": {
    "message": "模型不存在"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ErrorPayload`

### DonePayload

用于 `DONE` 消息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `reason` | `String` | 停止原因 |

```json
{
  "type": "DONE",
  "data": {
    "reason": "已停止"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.DonePayload`

### ToolCallStartPayload

用于 `TOOL_CALL_START` 消息，表示 LLM 发起了一次工具调用。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 工具调用唯一 id，用于关联 TOOL_CALL_START 与 TOOL_CALL_END |
| `name` | `String` | 工具名称 |
| `arguments` | `String` | 工具参数（JSON 格式字符串） |

```json
{
  "type": "TOOL_CALL_START",
  "data": {
    "id": "call_abc123",
    "name": "search_files",
    "arguments": "{\"query\": \"test\"}"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ToolCallStartPayload`

### ToolCallEndPayload

用于 `TOOL_CALL_END` 消息，表示 LLM 发起的工具调用已执行完成。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 工具调用唯一 id，与 TOOL_CALL_START 的 id 对应 |
| `name` | `String` | 工具名称 |
| `result` | `String` | 工具执行结果 |

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_abc123",
    "name": "search_files",
    "result": "[\"file1.txt\", \"file2.txt\"]"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ToolCallEndPayload`

---

## 协议流程

### 消息处理时序

```
客户端                    服务端
  |                         |
  |--- START_SESSION ------>|  第一条消息必须为 START_SESSION
  |<--- SESSION_ACK -------|  返回本次确认的会话 ID
  |                         |
  |--- CHAT --------------->|  后续消息需先通过 START_SESSION
  |<--- TOOL_CALL_START ---|  LLM 决定调用工具（id: call_xxx）
  |                         |  服务端执行工具调用
  |<--- TOOL_CALL_END -----|  工具执行完成（id: call_xxx）
  |<--- TEXT "回复内容" -----|  LLM 最终回复
  |                         |
  |--- STOP --------------->|  停止当前响应
  |<--- DONE "已停止" ------|
  |                         |
  |--- CHAT --------------->|  可继续发送新消息
  |<--- TEXT "回复内容" -----|
  |                         |
    （WebSocket 连接关闭）
```

### 约束规则

1. **START_SESSION**：每条 WebSocket 连接的第一条消息必须为 `START_SESSION` 类型，且整个连接生命周期内仅允许发送一次。
2. **会话 ID**：`START_SESSION` 的 `data` 中可传入 `sessionId` 字符串指定会话 ID，不传则由服务端生成 UUID。服务端通过 `SESSION_ACK` 消息返回最终确定的会话 ID。
3. **消息顺序**：`START_SESSION` 完成前拒绝所有其他消息类型。
3. **模型权限**：`CHAT` 消息中指定的模型，其 `uid` 必须为 0（公共模型）或等于当前用户 ID，否则返回 `ERROR`。

### 错误码

所有错误响应使用 `LlmMessageType.ERROR`，数据在 `data.message` 字段中：

```json
{
  "type": "ERROR",
  "data": {
    "message": "错误描述"
  }
}
```

常见错误：

| 场景 | 响应 message |
|------|-------------|
| JSON 解析失败 | `消息格式错误，无法解析` |
| 消息类型为空 | `消息类型不能为空` |
| 重复 START_SESSION | `START_SESSION 仅允许发送一次` |
| 未发送 START_SESSION | `请先发送 START_SESSION 消息开启会话` |
| CHAT 缺少参数 | `CHAT 消息缺少 modelId 或 content` |
| 模型不存在 | `模型不存在` |
| 无权访问模型 | `无权访问该模型` |
| 未知消息类型 | `未知消息类型: xxx` |

---

## 完整示例

### 正常对话

```
→ {"type":"START_SESSION","data":{}}
← {"type":"SESSION_ACK","data":{"sessionId":"550e8400-e29b-41d4-a716-446655440000"}}

→ {"type":"CHAT","data":{"modelId":1,"content":"你好"}}
← {"type":"TEXT","data":{"content":"你好，我是xxx模型，有什么能帮助我吗？"}}

→ {"type":"CHAT","data":{"modelId":1,"content":"今天天气如何？"}}
← {"type":"TEXT","data":{"content":"..."}}

→ {"type":"STOP","data":null}
← {"type":"DONE","data":{"reason":"已停止"}}
```

### 异常场景

```
→ {"type":"CHAT","data":{"modelId":1,"content":"你好"}}
← {"type":"ERROR","data":{"message":"请先发送 START_SESSION 消息开启会话"}}

→ {"type":"START_SESSION","data":{}}
← {"type":"SESSION_ACK","data":{"sessionId":"550e8400-e29b-41d4-a716-446655440000"}}

→ {"type":"START_SESSION","data":{}}
← {"type":"ERROR","data":{"message":"START_SESSION 仅允许发送一次"}}
```
