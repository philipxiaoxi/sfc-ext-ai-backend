# LLM 适配器系统 — 前后端联调变更

> 对应 commit: 2026-07-11  
> 涉及模块: sfc-ext-ai

---

## 一、新增接口

### `GET /api/ai/adapter/list`

查询系统当前支持的所有 LLM 提供商适配器。

**响应格式：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": "openai",
      "name": "OpenAI",
      "icon": "mdi-robot-outline"
    },
    {
      "id": "deepseek",
      "name": "DeepSeek",
      "icon": "/some/image.png"
    }
  ]
}
```

| 字段     | 类型     | 说明                                                                   |
|--------|--------|----------------------------------------------------------------------|
| `id`   | string | 适配器标识，提交 provider 时写入 adapter 字段                                     |
| `name` | string | 显示名称                                                                 |
| `icon` | string | 图标标识，Material Design Icon / HTTP URL / base64，直接使用 CommonIcon 组件渲染即可 |

---

## 二、对象新增

### AdapterInfo

前后端通过 `/api/ai/adapter/list` 接触到此对象，无需手动构造。

| 字段     | 类型     | 说明                                                        |
|--------|--------|-----------------------------------------------------------|
| `id`   | string | 适配器标识                                                     |
| `name` | string | 显示名称                                                      |
| `icon` | string | 图标标识（Material Icon / URL / base64），直接使用 CommonIcon 组件渲染即可 |

---

## 三、对象字段变更

### LlmProvider（仅影响接口出入参，不影响数据库结构变更）

| 变更类型 | 字段             | 旧值                         | 新值                               |
|------|----------------|----------------------------|----------------------------------|
| 删除   | `protocolType` | `"OpenAI"` / `"Anthropic"` | 不再使用                             |
| 新增   | `adapter`      | —                          | string，如 `"openai"`、`"deepseek"` |

**前端在 provider 管理页面的变化：**

- **保存 provider 时**，不再提交 `protocolType`，改为提交 `adapter` 字段，值为 `/api/ai/adapter/list` 返回的 `id`。
- **编辑 provider 时**，`adapter` 字段供编辑（下拉选择建议从 `/api/ai/adapter/list` 动态获取）。
- **列表/详情展示时**，显示的协议信息改为显示 `adapter`。

### ProviderVo（列表/详情接口响应）

| 变更类型 | 字段             | 旧值                | 新值                 |
|------|----------------|-------------------|--------------------|
| 替换   | `protocolType` | `"OpenAI"` 等 enum | → `adapter` string |

涉及接口：

- `GET /api/ai/provider/list`
- `GET /api/ai/provider/get`
- `POST /api/ai/provider/save`（影响请求体）
- `GET /api/ai/query/providersWithModels`（嵌套在 `ProviderVo` 中）

---

## 四、WebSocket 消息对象变更

### TextPayload（`TEXT` 类型消息负载）

WebSocket chat 流中 `TEXT` 消息的 data 对象新增 `reasoningContent` 字段。

| 字段                | 类型     | 说明                        |
|-------------------|--------|---------------------------|
| `content`         | string | 模型回复文本（已有）                 |
| `reasoningContent` | string? | 模型推理/思考链内容（CoT），可为 null |

**示例响应片段：**

```json
{
  "type": "TEXT",
  "data": {
    "content": "最终答案...",
    "reasoningContent": "思考过程..."
  }
}
```

> 当前仅 DeepSeek 适配器会填充 `reasoningContent`，OpenAI 及默认适配器为 null。

---

## 五、前端适配 checklist

- [ ] provider 保存表单：`protocolType` → `adapter`（单选下拉，选项从 `/api/ai/adapter/list` 获取）
- [ ] provider 列表页：表头/列显示从 `protocolType` 改为 `adapter`
- [ ] 新建/编辑 provider 时，检查 `adapter` 是否已选
- [ ] 适配器管理：如有管理页面，可调用 `/api/ai/adapter/list` 展示系统支持的适配器
