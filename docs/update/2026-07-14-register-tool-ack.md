# 工具注册确认消息（REGISTER_TOOL_ACK）— WebSocket 消息协议变更

> 对应日期：2026-07-14
> 涉及模块：sfc-ext-ai

---

## 变更概述

1. **新增 `REGISTER_TOOL_ACK` 响应消息** — 客户端发送 `REGISTER_TOOL` 后，服务端不再回复 `TEXT`，改为返回专用确认消息
2. **新增枚举值** — `LlmMessageType.REGISTER_TOOL_ACK`

---

## 一、新增服务端响应

### REGISTER_TOOL_ACK（服务端 → 客户端）

注册工具成功后，服务端发送 `REGISTER_TOOL_ACK`，`data` 为**纯字符串**（工具名称），而非 JSON 对象。

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 固定为 `"REGISTER_TOOL_ACK"` |
| `data` | `String` | 已注册的工具名称 |

```json
{
  "type": "REGISTER_TOOL_ACK",
  "data": "get_weather"
}
```

> ⚠️ 注意：与大多数消息（data 为 JSON 对象）不同，`REGISTER_TOOL_ACK` 的 `data` 是**裸字符串**。前端解析时应直接取值，无需按对象解构。

---

## 二、前端适配说明

### 消息监听

在 WebSocket 消息分发器中新增对 `REGISTER_TOOL_ACK` 类型的处理：

```javascript
// 伪代码示例
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  switch (msg.type) {
    case 'REGISTER_TOOL_ACK':
      // data 为工具名称字符串（非对象）
      onToolRegistered(msg.data);
      break;
    // ... 其他消息类型
  }
};
```

### 注意事项

- `data` 是**字符串**，而非 JSON 对象，与 `SESSION_ACK`、`TEXT` 等消息的数据结构不同
- 前端不需要对 `REGISTER_TOOL_ACK` 做特殊展示，通常仅用于确认注册成功后的状态更新（如 UI 上标记工具已注册）
- `REGISTER_TOOL_ACK` 仅表示服务端已收到并记录了工具注册，不表示 LLM 已使用该工具

---

## 三、影响范围

以下文档已同步更新：

| 文档 | 变更内容 |
|------|---------|
| `websocket-protocol.md` | 服务端消息表新增 `REGISTER_TOOL_ACK`；时序图和约束规则中替换原 `TEXT` 确认方式 |
| `2026-07-13-dynamic-tool-registration.md` | 注册响应的示例从 `TEXT` 修改为 `REGISTER_TOOL_ACK`；时序图同步更新 |

---

## 四、前端适配 checklist

- [ ] WebSocket 消息分发器中新增 `REGISTER_TOOL_ACK` 类型分支
- [ ] 解析时注意 `data` 为裸字符串，直接赋值，不做 JSON 对象解构
- [ ] 注册成功后更新前端工具列表状态（如标记为已注册、禁用注册按钮等）
