# 用户中断响应（STOP）与工具调用状态反馈 — WebSocket 消息协议变更

> 对应日期：2026-07-20
> 涉及模块：sfc-ext-ai

---

## 变更概述

1. **`STOP` 消息从空操作变为有效操作** — 用户发送 `STOP` 后，服务端实际取消 LLM 流、标题生成、工具调用（含内建服务端工具硬中断），并发送一条或多条 `TOOL_CALL_END` + 一条 `DONE`
2. **`TOOL_CALL_END` 新增 `status` 与 `errorMessage` 字段** — 客户端可区分工具调用是成功、异常失败还是被用户中断
3. **新增枚举类型** — `ToolCallStatus`（`SUCCESS` / `ERROR` / `CANCELLED`）
4. **`DONE` 多发送场景去重** — 正常完成与 `STOP` 间的竞态通过去重机制保证 `DONE` 最多发送一次

---

## 一、`TOOL_CALL_END` 消息结构变更

### 新增字段

| 字段 | 类型 | 必含 | 说明 |
|------|------|------|------|
| `status` | `String` | 是 | 工具调用执行状态：`SUCCESS` / `ERROR` / `CANCELLED` |
| `errorMessage` | `String` | 否 | 错误信息（`ERROR` 或 `CANCELLED` 时有值） |

### 三种状态的 payload 示例

**成功（`SUCCESS`）：**

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_001",
    "name": "search_files",
    "result": "[\"a.txt\", \"b.txt\"]",
    "status": "SUCCESS"
  }
}
```

**失败（`ERROR`）：**

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_001",
    "name": "search_files",
    "status": "ERROR",
    "errorMessage": "文件不存在"
  }
}
```

**用户中断（`CANCELLED`）：**

```json
{
  "type": "TOOL_CALL_END",
  "data": {
    "id": "call_001",
    "name": "search_files",
    "status": "CANCELLED",
    "errorMessage": "用户中断了工具调用"
  }
}
```

> ⚠️ 兼容性：`status` 和 `errorMessage` 为新增可选字段，旧客户端忽略未知字段即可。但建议前端尽快适配，否则无法区分工具调用失败与中断。

---

## 二、`STOP` 行为变更

### 变更前

`STOP` 仅发送 `DONE {"reason": "已停止"}`，不实际取消任何操作。LLM 流和工具调用在后台继续运行。

### 变更后

`STOP` 实际中断所有进行中的操作，中断完成后回复客户端。消息发送顺序如下：

```
<--- TOOL_CALL_START   【前置，STOP 之前已发送】
...
--- STOP  ------------>  用户发送中断
<--- TOOL_CALL_END(id, name, status=CANCELLED, errorMessage=...)  被中断的工具（0~N 条）
<--- TOOL_CALL_END(id, name, status=CANCELLED, errorMessage=...)  多个工具可能各自有一条
<--- DONE(reason="已停止")  响应结束
```

### 中断范围

| 中断对象 | 方式 | 影响 |
|---------|------|------|
| LLM 响应流 | `Disposable.dispose()` | 不再推送后续 `TEXT` |
| 异步标题生成 | `Future.cancel(true)` | 不推送 `TITLE_UPDATE` |
| 客户端中介工具（等待 `TOOL_ACK`） | `CompletableFuture.cancel(true)` | 阻塞解除，LLM 侧抛异常 |
| 内建服务端工具（文件搜索/IO 等） | `Future.cancel(true)` → 虚拟线程 `Thread.interrupt()` | 阻塞 IO 抛 `InterruptedException`，工具终止 |

除内建服务端工具外，其余均为非阻塞取消。内建工具的硬中断依赖虚拟线程的阻塞 IO 对 `Thread.interrupt()` 的响应——大部分 NIO 操作会抛 `ClosedByInterruptException`，CPU 密集任务仅设置中断标志位，实际执行至下一检查点。

---

## 三、对话记忆补偿

LLM 已在响应中发起工具调用但尚未返回结果时被中断，对话记忆（ChatMemory）中会残留一条带 `toolCalls` 但缺少对应 tool 结果消息的 assistant 记录。在下次加载会话时，LLM API 可能因此拒绝请求（400）。

服务端在 `STOP` 和 WebSocket 连接关闭时，会为每个悬空的工具调用自动补写一条占位结果消息（`"工具调用已被用户中断"`），保证记忆序列完整性。

---

## 四、三种场景的消息序列对比

| 场景 | 消息序列（按时间顺序） |
|------|----------------------|
| **无工具调用正常完成** | `TEXT...` → `DONE(reason="已完成")` |
| **带工具调用正常完成** | `TOOL_CALL_START` → `TOOL_CALL_END(status=SUCCESS)` → `TEXT...` → `DONE(reason="已完成")` |
| **工具执行异常失败** | `TOOL_CALL_START` → `TOOL_CALL_END(status=ERROR, errorMessage=...)` → 后续 LLM 重试或回复 → `DONE(reason="已完成")` |
| **用户主动中断** | `TOOL_CALL_START` → `TOOL_CALL_END(status=CANCELLED)` × N → `DONE(reason="已停止")` |
| **无工具调用时中断** | （无 TOOL_CALL_END）→ `DONE(reason="已停止")` |

---

## 五、`DONE` 去重说明

正常完成时 Flux 管线触发 `doOnComplete` 发出 `DONE(reason="已完成")`；用户发送 `STOP` 时 `handleStop` 发出 `DONE(reason="已停止")`。

两个路径存在极小竞态窗口（流刚好在 STOP 之前自然完成）。服务端内部使用 `AtomicBoolean CAS` 保证 `DONE` **最多发送一次**，具体规则：

| 先后顺序 | 实际发送 |
|----------|---------|
| 正常完成先到达，STOP 后到达 | `DONE(reason="已完成")` 仅发一次 |
| STOP 先到达，流被取消 | `DONE(reason="已停止")` 仅发一次 |
| 两者同时（毫秒级竞态） | 仅最先执行者发送，另一路径的 `DONE` 被静默丢弃 |

前端在收到任意 `DONE` 后，仍应处理其后的 `reason` 字段完成状态展示即可，无需额外逻辑。

---

## 六、前端适配说明

### WebSocket 消息分发器

`TOOL_CALL_END` 消息解析时提取 `status` 和 `errorMessage`：

```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'TOOL_CALL_END') {
    const { id, name, result, status, errorMessage } = msg.data;
    // status: 'SUCCESS' | 'ERROR' | 'CANCELLED'
    if (status === 'CANCELLED') {
      // 工具被用户中断 —— 前端不应等待该工具的结果
      // 更新 UI：该工具卡片/日志中标记为"已中断"
    } else if (status === 'ERROR') {
      // 工具执行失败 —— 可展示 errorMessage
    } else {
      // 工具执行成功 —— 展示 result
    }
  }
};
```

### UI 处理建议

| `TOOL_CALL_END.status` | UI 展示 |
|------------------------|---------|
| `SUCCESS` | 绿色/正常图标，展示 result 内容 |
| `ERROR` | 红色/警告图标，展示 errorMessage |
| `CANCELLED` | 灰色/中断图标，可展示"用户已中断" |

收到 `DONE(reason="已停止")` 时，同样可标记为 UI 上的"响应已中断"状态。收到 `DONE(reason="已完成")` 时，标记为"响应完成"。

---

## 七、影响范围

以下文档已同步更新：

| 文档 | 变更内容 |
|------|---------|
| `websocket-protocol.md` | `TOOL_CALL_END` 新增 `status`/`errorMessage` 字段说明及三种示例；`STOP` 行为说明重写；时序图更新；示例对话更新 |
| `2026-07-20-stop-tool-status.md`（本文） | 本次变更专项说明 |

---

## 八、前端适配 checklist

- [ ] `TOOL_CALL_END` 解析时提取 `status`、`errorMessage` 字段
- [ ] 根据 `status` 区分工具调用成功 / 失败 / 被中断，分别展示不同 UI
- [ ] 收到 `DONE(reason="已停止")` 时，UI 标记当前响应为用户中断（区别于自然完成）
- [ ] 理解 `TOOL_CALL_END(status=CANCELLED)` 可能在 `DONE` 之前到达，也可能因竞态在 `DONE` 之后到达少量残余；建议在收到 `DONE` 后停止等待工具结果
- [ ] 无需因 `DONE` 去重做特殊适配，自然处理 `reason` 字段即可

---

## 九、相关 Java 类参考

| Java 类 | 路径 |
|---------|------|
| `ToolCallStatus` | `com.sfc.ai.model.chat.payload.ToolCallStatus` |
| `ToolCallEndPayload`（新增 `status`、`errorMessage`） | `com.sfc.ai.model.chat.payload.ToolCallEndPayload` |
| `DonePayload`（不变） | `com.sfc.ai.model.chat.payload.DonePayload` |
