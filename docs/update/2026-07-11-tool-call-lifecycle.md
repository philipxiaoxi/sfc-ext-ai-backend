# 工具调用生命周期 — WebSocket 消息协议变更

> 对应日期: 2026-07-11
> 涉及模块: sfc-ext-ai

---

## 一、新增枚举值

### `LlmMessageType` 新增

| 枚举值 | 说明 | 对应 Payload |
|--------|------|-------------|
| `TOOL_CALL_START` | 工具调用开始（LLM 决定调用工具时触发） | `ToolCallStartPayload` |
| `TOOL_CALL_END` | 工具调用结束（工具执行完成时触发） | `ToolCallEndPayload` |

> `TOOL_CALL` 已废弃，由 `TOOL_CALL_START` + `TOOL_CALL_END` 替代，前端不再监听 `TOOL_CALL` 类型。

---

## 二、新增 Payload

### ToolCallStartPayload

`TOOL_CALL_START` 消息的 `data` 负载。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 工具调用唯一 id，用于关联 TOOL_CALL_START 与 TOOL_CALL_END |
| `name` | String | 工具名称 |
| `arguments` | String | 工具参数（JSON 格式字符串） |

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

`TOOL_CALL_END` 消息的 `data` 负载。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 工具调用唯一 id，与 TOOL_CALL_START 的 id 对应 |
| `name` | String | 工具名称 |
| `result` | String | 工具执行结果 |

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

## 三、WebSocket 消息生命周期变更

工具调用引入完整的生命周期消息，由 `TOOL_CALL_START` — `TOOL_CALL_END` 配对替代单条 `TOOL_CALL` 消息。

### 旧协议

```
CHAT → TOOL_CALL（含 name + arguments + result）→ TEXT（LLM 最终回复）
```

### 新协议

```
CHAT → TOOL_CALL_START（id + name + arguments）→ 工具执行 → TOOL_CALL_END（id + name + result）→ TEXT（LLM 最终回复）
```

### 完整时序示例

```
→ {"type":"CHAT","data":{"modelId":1,"content":"帮我找一下 test 文件"}}
← {"type":"TOOL_CALL_START","data":{"id":"call_001","name":"search_files","arguments":"{\"query\":\"test\"}"}}
← {"type":"TOOL_CALL_END","data":{"id":"call_001","name":"search_files","result":"[\"file1.txt\"]"}}
← {"type":"TEXT","data":{"content":"找到文件：file1.txt"}}
```

### 挂起与恢复窗口

`TOOL_CALL_START` 与 `TOOL_CALL_END` 之间的窗口期对应工具执行阶段：

- 前端可在此期间展示"正在调用工具"的加载状态（如 "正在搜索文件..."）
- `id` 字段可用于关联前后端状态，支持同时多个工具调用的场景
- 工具执行完成后发送 `TOOL_CALL_END`，前端可据此更新界面

### 工具调用执行流程

```
LLM 响应 → 检测 tool call → SimpleToolCallAdvise.after() → 发送 TOOL_CALL_START, 记录 pending id
         → ToolCallingAdvisor 执行工具
         → SimpleToolCallAdvise.before() → 匹配 ToolResponseMessage id → 发送 TOOL_CALL_END, 移除 pending id
```

---

## 四、前后端适配 checklist

- [ ] 前端新增对 `TOOL_CALL_START` 消息类型的处理，展示工具名称和参数
- [ ] 前端新增对 `TOOL_CALL_END` 消息类型的处理，展示工具执行结果并清除加载状态
- [ ] 前端可移除或保留对旧 `TOOL_CALL` 类型的处理（建议兼容保留）
- [ ] 前端使用 `id` 字段关联同一工具调用的开始和结束，支持并发场景
