# AI 工具权限审批 — 前端对接文档

## 概述

AI 工具调用权限审批系统为敏感操作（如删除、移动、复制文件）增加了用户确认机制。前端需要处理 **权限请求弹窗**、**授权模式切换** 和 **批准/拒绝操作** 三个交互场景。

---

## 新增消息类型

### 服务端 → 客户端

| 类型 | 说明 | 触发条件 |
|------|------|---------|
| `PERMISSION_REQUEST` | 请求用户批准敏感工具调用 | AI 调用敏感工具且当前为普通授权模式 |

### 客户端 → 服务端

| 类型 | 说明 |
|------|------|
| `APPROVE` | 用户批准或拒绝工具执行 |
| `SWITCH_MODE` | 切换授权模式 |

---

## 交互流程

### 1. 敏感工具调用 → 权限请求弹窗

```
← TOOL_CALL_START  {id, name, arguments}
← PERMISSION_REQUEST {toolCallId, toolName, purpose, arguments}
```

收到 `PERMISSION_REQUEST` 后，前端应：

- **弹出确认对话框**，向用户展示以下信息：
  - 调用的工具名称（如 `delete`）
  - 用途说明（如 `"删除文件 private:/doc/report.pdf"`）
  - 工具参数（如有需要可展开查看）
- 提供 **"批准"** 和 **"拒绝"** 两个按钮
- 在用户做出选择前，可展示操作等待状态

### 2. 用户批准

```json
{
  "type": "APPROVE",
  "data": {
    "toolCallId": "call_001",
    "approved": true
  }
}
```

发送后服务端会执行工具并通过 `TOOL_CALL_END` 返回结果：

```
← TOOL_CALL_END {id, name, result, status: "SUCCESS"/"ERROR"}
```

### 3. 用户拒绝

```json
{
  "type": "APPROVE",
  "data": {
    "toolCallId": "call_001",
    "approved": false
  }
}
```

服务端会返回：

```
← TOOL_CALL_END {id, name, status: "CANCELLED", errorMessage: "用户拒绝了操作"}
```

---

## 授权模式切换

### 模式说明

| 模式 | 枚举值 | 行为 |
|------|--------|------|
| **普通授权** | `NORMAL` | 敏感工具调用需用户确认（默认） |
| **完全授权** | `FULL` | 敏感工具直接执行，无需确认 |

### 切换请求

```json
{
  "type": "SWITCH_MODE",
  "data": {
    "mode": "FULL"
  }
}
```

### 切换响应

服务端返回一条 TEXT 消息确认：

```
← TEXT {content: "授权模式已切换为: 完全授权"}
```

### 模式生命周期

- 每个 WebSocket 连接建立时默认为 `NORMAL`
- 切换仅对当前连接生效
- 连接断开、重新建立后恢复为 `NORMAL`
- 切换不持久化到服务端

### LLM 查询当前模式

AI 内建工具 `get_auth_mode` 可用于查询当前授权模式：

- **工具名称**: `get_auth_mode`
- **参数**: 无
- **返回值**: `"完全授权"` 或 `"普通授权"`

---

## 前端实现建议

### 权限请求对话框

收到 `PERMISSION_REQUEST` 时的 UI 交互：

1. **展示对话框**：标题可设为 `"操作需要批准"`，内容显示用途说明
2. **参数详情**（可选）：可折叠展示工具调用的完整参数
3. **操作按钮**：`批准`（绿色/主按钮）、`拒绝`（红色/次按钮）
4. **多个待批准操作**：每个 `toolCallId` 独立管理，可逐个处理

### 授权模式切换 UI

可在 AI 聊天界面中增加：
- **模式切换开关**（如 toggle 或按钮）：显示"完全授权" / "普通授权"
- **当前模式指示**：在聊天区域显示当前授权模式状态
- **切换响应**：收到 TEXT 确认后更新 UI 状态

### 错误处理

| 场景 | 错误消息 | 处理建议 |
|------|---------|---------|
| `toolCallId` 不存在 | `未找到待批准的工具调用: xxx` | 忽略该错误（可能已超时或被其他响应处理） |

### 竞态说明

- `PERMISSION_REQUEST` 可能在 LLM 响应流中间发送
- 用户批准/拒绝后，LLM 会继续生成后续回复（`TEXT` 消息）
- 已发送 `APPROVE` 后忽略相同 `toolCallId` 的后续消息

---

## 完整示例

```
→ WS CONNECT

→ {"type":"START_SESSION","data":{}}
← {"type":"SESSION_ACK","data":{"sessionId":"abc-123"}}

→ {"type":"CHAT","data":{"modelId":1,"content":"帮我删除 /doc 目录下的 report.pdf"}}
← {"type":"TOOL_CALL_START","data":{"id":"call_001","name":"delete","arguments":"{\"disk\":\"private\",\"path\":\"/doc\",\"name\":\"report.pdf\"}"}}
← {"type":"PERMISSION_REQUEST","data":{"toolCallId":"call_001","toolName":"delete","purpose":"删除文件 private:/doc/report.pdf","arguments":{"disk":"private","path":"/doc","name":"report.pdf"}}}

  [弹出确认对话框，用户点击"批准"]

→ {"type":"APPROVE","data":{"toolCallId":"call_001","approved":true}}
← {"type":"TOOL_CALL_END","data":{"id":"call_001","name":"delete","result":"删除成功","status":"SUCCESS"}}
← {"type":"TEXT","data":{"content":"已成功删除 report.pdf"}}
← {"type":"DONE","data":{"reason":"已完成","modelId":"1","time":1523}}
```
