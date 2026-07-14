# 动态工具注册 — WebSocket 消息协议变更

> 对应日期：2026-07-13
> 涉及模块：sfc-ext-ai

---

## 变更概述

1. **新增 `REGISTER_TOOL` 消息** — 客户端可在 `CHAT` 前注册自定义工具定义
2. **新增 `TOOL_CALL_REQ` / `TOOL_ACK` 消息对** — 动态注册的工具执行时，服务端通过消息对将执行权委托给客户端
3. **新增 Payload** — `RegisterToolPayload`、`ToolCallAckPayload`

---

## 一、新增消息类型

### 用户消息 `REGISTER_TOOL`

客户端在 `CHAT` 前注册一个动态工具。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `String` | 是 | 固定为 `"REGISTER_TOOL"` |
| `data.name` | `String` | 是 | 工具名称，在当前会话中唯一 |
| `data.description` | `String` | 是 | 工具描述，供 LLM 理解工具用途 |
| `data.parameters` | `String` | 是 | JSON Schema 格式的参数字符串 |

```json
{
  "type": "REGISTER_TOOL",
  "data": {
    "name": "get_weather",
    "description": "获取指定城市的天气信息",
    "parameters": "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名称\"}},\"required\":[\"city\"]}"
  }
}
```

服务端回复（确认注册成功）：

```json
{
  "type": "TEXT",
  "data": {
    "content": "工具已注册: get_weather"
  }
}
```

### TOOL_CALL_REQ（服务端 → 客户端）

当 LLM 决定调用一个**通过 REGISTER_TOOL 注册的动态工具**时，服务端不自行执行，而是发送 `TOOL_CALL_REQ` 将执行委托给客户端。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 工具调用唯一 ID，用于关联 TOOL_CALL_REQ 与 TOOL_ACK |
| `name` | `String` | 工具名称 |
| `arguments` | `String` | 工具参数（JSON 格式字符串） |

```json
{
  "type": "TOOL_CALL_REQ",
  "data": {
    "id": "req_uuid_xxx",
    "name": "get_weather",
    "arguments": "{\"city\":\"北京\"}"
  }
}
```

### TOOL_ACK（客户端 → 服务端）

客户端执行完工具后，回复 `TOOL_ACK` 将结果交回服务端。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `String` | 是 | 固定为 `"TOOL_ACK"` |
| `data.id` | `String` | 是 | 与 TOOL_CALL_REQ 的 id 一致 |
| `data.name` | `String` | 是 | 工具函数名称 |
| `data.arguments` | `Object` | 是 | 调用参数（与 TOOL_CALL_REQ 中的参数一致） |
| `data.result` | `String` | 是 | 工具执行结果 |

```json
{
  "type": "TOOL_ACK",
  "data": {
    "id": "req_uuid_xxx",
    "name": "get_weather",
    "arguments": {"city": "北京"},
    "result": "北京：晴，25°C"
  }
}
```

---

## 二、时序流程

### 动态工具注册与调用

```
客户端                                                      服务端
  |                                                           |
  |--- START_SESSION -------------------------------------->|
  |<--- SESSION_ACK {sessionId} ----------------------------|
  |                                                           |
  |--- REGISTER_TOOL {name, description, parameters} ------->|
  |<--- TEXT "工具已注册: get_weather" ----------------------|
  |                                                           |
  |--- CHAT {modelId, content: "北京天气如何？"} ----------->|
  |                                                           |
  |  （LLM 决定调用 get_weather 工具）                          |
  |                                                           |
  |<--- TOOL_CALL_REQ {id, name, arguments} -----------------|  服务端委托客户端执行
  |                                                           |
  |  （客户端自行处理，例如调外部 API）                          |
  |                                                           |
  |--- TOOL_ACK {id, name, arguments, result} -------------->|  客户端返回结果
  |                                                           |
  |  （服务端将结果交回 LLM）                                   |
  |                                                           |
  |<--- TEXT "北京当前晴，25°C" -----------------------------|
  |<--- DONE {reason: "已完成"} ------------------------------|
```

### 内建工具 vs 动态注册工具的区别

| 特性 | 内建工具（CommonTools） | 动态注册工具（REGISTER_TOOL） |
|------|------------------------|-----------------------------|
| 注册时机 | 服务端启动时固定注册 | 运行时通过 WebSocket 注册 |
| 执行方式 | 服务端自动执行 | 客户端需通过 TOOL_CALL_REQ/TOOL_ACK 协作 |
| 生命周期 | 永久存在 | 仅当前 WebSocket 会话有效（连接关闭后清除） |
| 流式兼容 | 正常流式 | 流式暂停，等待 TOOL_ACK 后继续（可能长达 60 秒） |

### 重要说明

- **多个 `REGISTER_TOOL`**：可在一次会话中注册多个工具，每个工具需要唯一的 `name`
- **`CHAT` 前注册**：`REGISTER_TOOL` 最好在 `CHAT` 前发送，`CHAT` 时服务端会快照当前已注册的工具列表。`CHAT` 后注册的工具不影响当前正在进行的 LLM 调用
- **超时**：服务端等待 `TOOL_ACK` 最多 **60 秒**，超时后 LLM 调用抛出异常并中断
- **连接关闭**：WebSocket 连接关闭时（包括用户主动关闭），所有未完成的 `TOOL_CALL_REQ` 将被取消
- **不影响内建工具**：`REGISTER_TOOL` 注册的工具不会影响 `CommonTools` 中的内建工具（如 `getNowTime`、`listPublicNetDiskFiles`），两者在 LLM 视角中同时可用

---

## 三、前端适配 checklist

- [ ] 新增对 `UserMessageType.REGISTER_TOOL` 消息的发送支持，允许用户在 `CHAT` 前注册自定工具
- [ ] 新增对 `LlmMessageType.TOOL_CALL_REQ` 的监听处理
- [ ] 收到 `TOOL_CALL_REQ` 后，根据 `name` 和 `arguments` 执行对应操作（如调外部 API、弹窗让用户手动输入等）
- [ ] 执行完成后，发送 `TOOL_ACK` 消息并将 `id`、`name`、`arguments`、`result` 完整传回
- [ ] 实现超时容错（60 秒限制），确保不会长时间不响应 `TOOL_CALL_REQ`
- [ ] WebSocket 断开时主动 cancel 未完成的工具调用
