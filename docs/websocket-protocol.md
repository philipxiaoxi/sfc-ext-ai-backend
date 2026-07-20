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
| `REGISTER_TOOL` | 注册动态工具 | `RegisterToolPayload` |
| `TOOL_ACK` | 工具调用确认（响应 TOOL_CALL_REQ） | `ToolCallAckPayload` |
| `STOP` | 停止响应 | 无 |

> **STOP 中断行为**：`STOP` 会中断当前进行中的所有 AI 操作，包括：
> - **LLM 响应流**：立即取消（dispose），后续不再推送 `TEXT` 消息。
> - **异步标题生成**：立即取消，不推送 `TITLE_UPDATE`。
> - **客户端中介工具调用**：取消等待 `TOOL_ACK` 的阻塞，向 LLM 侧抛出异常。
> - **内建服务端工具调用**：对正在执行的虚拟线程发起 `Thread.interrupt()` 硬中断，阻塞 IO 操作（如文件遍历、文件读取）将抛出中断异常并终止。
>
> 被中断的工具会在 `DONE` 之前收到一条或多条 `TOOL_CALL_END` 消息（status 为 `CANCELLED`），客户端可据此区分工具是正常执行、失败还是被用户中断：
>
> - **正常完成**：`TOOL_CALL_END(status=SUCCESS)` → 后续可接 `TEXT` → `DONE "已完成"`
> - **工具失败**：`TOOL_CALL_END(status=ERROR)` → 后续可接 `TEXT` → `DONE "已完成"`
> - **用户中断**：`TOOL_CALL_END(status=CANCELLED)` × N → `DONE "已停止"`
>
> 客户端在收到 `DONE "已停止"` 后，不应再期望收到与本次响应相关的任何消息。如因竞态收到少量残留消息，客户端可忽略 `DONE` 之后到达的残余消息。中断时未完成的流式回复不会保存到对话记忆（ChatMemory）；若中断前 LLM 已发起工具调用，该次调用请求会保留在记忆中，且服务端会为每个未完成的工具调用补写一条"工具调用已被用户中断"的占位结果，以保证后续对话的上下文完整性。

### 服务端消息类型（`LlmMessageType`）

| 枚举值 | 说明 | data Payload |
|--------|------|-------------|
| `SESSION_ACK` | 会话确认 | `SessionAckPayload` |
| `TEXT` | 普通文本消息 | `TextPayload` |
| `TITLE_UPDATE` | 对话标题更新 | `TitleUpdatePayload` |
| `THINKING_START` | 模型开始思考 | 待定 |
| `THINKING_END` | 模型思考结束 | 待定 |
| `TOOL_CALL_START` | 工具调用开始 | `ToolCallStartPayload` |
| `TOOL_CALL_END` | 工具调用结束 | `ToolCallEndPayload` |
| `TOOL_CALL` | 工具调用（已废弃，由 TOOL_CALL_START + TOOL_CALL_END 替代） | `ToolCallPayload` |
| `TOOL_CALL_REQ` | 工具调用请求（需要客户端执行并返回结果） | `ToolCallStartPayload` |
| `REGISTER_TOOL_ACK` | 工具注册确认 | `String`（工具名称） |
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

| 字段 | 类型       | 说明 |
|------|----------|------|
| `reason` | `String` | 停止原因 |
| `modelId` | `String` | 模型 ID（数据库主键） |
| `time` | `Long`   | 调用耗时（毫秒） |

```json
{
  "type": "DONE",
  "data": {
    "reason": "已完成",
    "modelId": "deepseek-v4-flash",
    "time": 3521
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.DonePayload`

### TitleUpdatePayload

用于 `TITLE_UPDATE` 消息，在首次 CHAT 后异步推送对话标题。

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `String` | 生成的对话标题（≤20 字） |
| `conversationId` | `String` | 对应的会话 ID |

```json
{
  "type": "TITLE_UPDATE",
  "data": {
    "title": "文件查找方法",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.TitleUpdatePayload`

### RegisterToolPayload

用于 `REGISTER_TOOL` 消息，客户端注册一个动态工具的定义。注册成功后服务端返回 `REGISTER_TOOL_ACK`（data 为工具名称字符串）。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `String` | 是 | 工具名称（在当前会话中唯一） |
| `description` | `String` | 是 | 工具描述，供 LLM 理解工具用途 |
| `parameters` | `String` | 是 | JSON Schema 字符串，定义工具参数 |

```json
{
  "type": "REGISTER_TOOL",
  "data": {
    "name": "my_custom_tool",
    "description": "自定义工具，用于执行 xxx 操作",
    "parameters": "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.RegisterToolPayload`

### ToolCallAckPayload

用于 `TOOL_ACK` 消息，客户端响应 `TOOL_CALL_REQ`，返回工具执行结果。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `String` | 是 | 工具调用 ID，与对应 TOOL_CALL_REQ 的 id 一致 |
| `name` | `String` | 是 | 工具函数名称 |
| `arguments` | `Object` | 是 | 调用参数（与 TOOL_CALL_REQ 中的参数一致） |
| `result` | `String` | 是 | 工具执行结果 |

```json
{
  "type": "TOOL_ACK",
  "data": {
    "id": "call_abc123",
    "name": "my_custom_tool",
    "arguments": {"input": "test"},
    "result": "执行成功"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ToolCallAckPayload`

### ToolCallStartPayload

用于 `TOOL_CALL_START` 消息，表示 LLM 发起了一次工具调用。也用于 `TOOL_CALL_REQ` 消息，表示服务端请求客户端执行工具。

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
| `result` | `String` | 工具执行结果（`SUCCESS` 时有值） |
| `status` | `String` | 工具调用执行状态：`SUCCESS`（成功） / `ERROR`（异常失败） / `CANCELLED`（被用户中断） |
| `errorMessage` | `String` | 错误信息（`ERROR` 或 `CANCELLED` 时有值） |

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_abc123",
    "name": "search_files",
    "result": "[\"file1.txt\", \"file2.txt\"]",
    "status": "SUCCESS"
  }
}
```

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_abc123",
    "name": "search_files",
    "status": "ERROR",
    "errorMessage": "文件不存在"
  }
}
```

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_abc123",
    "name": "search_files",
    "status": "CANCELLED",
    "errorMessage": "用户中断了工具调用"
  }
}
```

对应 Java 类：`com.sfc.ai.model.chat.payload.ToolCallEndPayload`

---

## 协议流程

### 消息处理时序

```
客户端                             服务端
  |                                  |
  |--- START_SESSION --------------->|  第一条消息必须为 START_SESSION
  |<--- SESSION_ACK ----------------|  返回本次确认的会话 ID
  |                                  |
   |--- REGISTER_TOOL --------------->|  （可选）注册动态工具定义
   |<--- REGISTER_TOOL_ACK ----------|  工具注册确认（data 为工具名称字符串）
  |                                  |
  |--- CHAT ------------------------>|  后续消息需先通过 START_SESSION
  |                                  |  （首次 CHAT 时异步生成标题）
  |<--- TITLE_UPDATE ---------------|  推送对话标题（首次对话时） data: {title, conversationId}
  |<--- TOOL_CALL_START ------------|  LLM 决定调用内建工具（id: call_xxx）
  |                                  |  服务端执行工具调用
  |<--- TOOL_CALL_END --------------|  内建工具执行完成（id: call_xxx）
  |<--- TOOL_CALL_REQ --------------|  LLM 调用动态注册工具，请求客户端执行（id: req_xxx）
  |--- TOOL_ACK -------------------->|  客户端返回工具执行结果（id: req_xxx）
  |<--- TEXT "回复内容" -------------|  LLM 最终回复
   |                                  |
   |--- STOP ------------------------>|  中断当前响应（LLM 流/工具调用/标题生成）
   |<--- TOOL_CALL_END --------------|  被中断的工具（id: call_xxx, status: CANCELLED）
   |<--- DONE "已停止" --------------|
  |                                  |
  |--- CHAT ------------------------>|  可继续发送新消息
  |<--- TEXT "回复内容" -------------|
  |                                  |
    （WebSocket 连接关闭）
```

### 约束规则

1. **START_SESSION**：每条 WebSocket 连接的第一条消息必须为 `START_SESSION` 类型，且整个连接生命周期内仅允许发送一次。
2. **会话 ID**：`START_SESSION` 的 `data` 中可传入 `sessionId` 字符串指定会话 ID，不传则由服务端生成 UUID。服务端通过 `SESSION_ACK` 消息返回最终确定的会话 ID。
3. **消息顺序**：`START_SESSION` 完成前拒绝所有其他消息类型。
4. **模型权限**：`CHAT` 消息中指定的模型，其 `uid` 必须为 0（公共模型）或等于当前用户 ID，否则返回 `ERROR`。
5. **标题生成**：对于全新的对话（`conversationId` 未在数据库中记录），首次 `CHAT` 消息时会异步生成标题。标题生成失败不影响正常对话流程。标题通过 `TITLE_UPDATE` 消息推送，客户端应在收到后更新对话列表的标题显示。
6. **工具注册**：`REGISTER_TOOL` 应在 `CHAT` 之前发送，`CHAT` 发送后注册的工具不会影响当前正在进行的 LLM 调用，但会影响下一次 `CHAT` 创建的 ChatClient。注册成功后服务端返回 `REGISTER_TOOL_ACK` 消息，`data` 为工具名称字符串。
7. **TOOL_CALL_REQ / TOOL_ACK**：当 LLM 调用动态注册的工具时，服务端会发送 `TOOL_CALL_REQ` 并阻塞等待客户端返回 `TOOL_ACK`。客户端应在接收到 `TOOL_CALL_REQ` 后尽快执行对应操作并回复 `TOOL_ACK`，否则将阻塞 LLM 响应（超时 60 秒后抛出异常）。

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
← {"type":"TITLE_UPDATE","data":{"title":"文件查找方法","conversationId":"550e8400-e29b-41d4-a716-446655440000"}}
← {"type":"TOOL_CALL_START","data":{"id":"call_001","name":"search_files","arguments":"{\"query\":\"test\"}"}}
← {"type":"TOOL_CALL_END","data":{"id":"call_001","name":"search_files","result":"[\"a.txt\"]","status":"SUCCESS"}}
← {"type":"TEXT","data":{"content":"文件查找中..."}}
← {"type":"DONE","data":{"reason":"已完成"}}

→ {"type":"CHAT","data":{"modelId":1,"content":"帮我搜索"}}
← {"type":"TOOL_CALL_START","data":{"id":"call_002","name":"search_files","arguments":"{\"query\":\"配置文件\"}"}}
← {"type":"TOOL_CALL_END","data":{"id":"call_002","name":"search_files","status":"ERROR","errorMessage":"目录不存在"}}
← {"type":"TEXT","data":{"content":"搜索时遇到错误"}}

→ {"type":"STOP","data":null}
← {"type":"TOOL_CALL_END","data":{"id":"call_003","name":"read_file","status":"CANCELLED","errorMessage":"用户中断了工具调用"}}
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
