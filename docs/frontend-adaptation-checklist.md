# 前端适配清单（sfc-ext-ai 插件 → sfc-ai-service 微服务）

> 面向：调用 AI 接口的前端工程。
> 背景：后端 `sfc-ext-ai`（宿主插件）已在 Phase 2 迁为独立微服务 `microservices/sfc-ai-service`。
> 本清单只列**前端需要关注**的变更，并明确区分「不用改」与「必须改」。

---

## 一、不用改的部分（已逐一核对，路径/协议/结构均未变）

| 项目 | 说明 |
|---|---|
| **REST 路径** | 全部保持不变，控制器前缀本就是 `/api/ai/**`：<br>`/api/ai/conversation/*`、`/api/ai/adapter/*`、`/api/ai/model/*`、`/api/ai/provider/*`、`/api/ai/query/*`、`/api/ai/sensitiveToolCall/*` |
| **WebSocket 端点** | 保持 `/api/ai/wschat`（协议见 [`docs/websocket-protocol.md`](websocket-protocol.md)，消息格式未变） |
| **响应信封** | 仍为 `{ code, businessCode, msg, data }`。旧 `JsonResult` 接口与新 `SfcJsonResult` **字段逐字一致**，前端解析逻辑无需改动 |
| **分页结构** | 仍为 `{ content, totalCount, totalPage }`。旧 `CommonPageInfo` 与新 `SfcPageInfo` **字段逐字一致** |
| **鉴权方式** | 仍是会话凭证（`Token` 请求头 / `token` Cookie）。网关会把凭证**原值透传**给下游服务 |
| **工具权限接口** | 见 [`docs/ai-tool-permission-frontend-api.md`](ai-tool-permission-frontend-api.md)，未变 |

**结论：前端对 AI 接口的 URL、请求体、响应体解析，基本不需要改。**

---

## 二、必须改的部分

### 1. 调用入口必须走网关（不再直连 core）

AI 接口现在由**独立的 sfc-ai-service**（端口 `8091`）提供，经**网关**（`sfc-gateway`）以
`Path=/api/ai/**` → `lb://sfc-ai-service` 转发。

- ✅ 正确：前端仍按同源方式请求 `/api/ai/**`，与其它接口一样经网关。
- ❌ 错误：把 `/api/ai/**` 指向 core 的 `8087` —— core 已不再加载 AI 插件，会 404。
- ⚠️ 若你的前端之前为 AI 单独配置了 baseURL/代理到 core，**请改成指向网关**。

### 2. 绝对不要自己发送 `X-Sfc-Uid`

网关在转发时统一注入信任头 `X-Sfc-Uid`，服务端**直接采信、不做校验**（框架期「信任模式」）。

- 前端**必须**不传这个头；传了等于可以冒充任意用户（越权）。
- 同理不要让浏览器能直连 `8091`：AI 服务只允许内网/本机暴露，对外唯一入口是网关。

### 3. 宿主管理端的「插件配置」AI 面板消失

插件期的 `src/main/resources/plugin-info.json` 与 `config-properties.json` 已随「去插件化」删除，
core 也不再加载本插件，因此：

- 管理端里由宿主插件系统渲染的 **AI 配置面板不再存在**（`llm-provider` / `llm-provider-manager` 等节点）。
- 但 AI 服务**自己已提供** LLM 提供商/模型的 REST CRUD，前端配置界面应改为调用它们：
  - `/api/ai/provider/list`、`/api/ai/provider/get`、`/api/ai/provider/save`、`/api/ai/provider/delete`
  - `/api/ai/model/list`、`/api/ai/model/get`、`/api/ai/model/save`、`/api/ai/model/delete`

> `plugin-info.json` 的 `autoLoad` 曾声明 `index.umd.js` / `style.css`，但**这两个文件本来就不在本后端仓库中**
> （全仓搜索为 0 个），所以「插件自动注入前端资源」这条路即使保留也无资产可注入；
> 若前端曾依赖该机制，请改为常规静态资源加载。

---

## 三、数据：历史 AI 数据不会自动出现（部署时需人工决策）

- 插件期，AI 的表（会话、消息、提供商、模型、敏感工具调用记录）建在**宿主的 `xyy.db`** 里
  （插件复用宿主的数据源）。
- 微服务化后，本服务使用**自己的数据库文件 `ai.db`**（`ddl-auto=update` 自动建表）。
- 因此**历史 AI 数据不会出现在微服务里**（与此前约定的「数据存储不做迁移，不管已有数据」一致）。

**前端可观察到的表现**：对话列表/历史消息为空、LLM 提供商与模型列表为空，需要重新配置或搬迁数据。

如果需要保留历史数据，二选一（属部署/运维动作，不在本次改造范围内）：

1. 把 `xyy.db` 中对应的 AI 表数据复制到 `ai.db`；
2. 或让 AI 服务临时用 `--spring.datasource.url=jdbc:sqlite:./xyy.db` 指向旧库启动（注意多进程同时写同一个 SQLite 文件有锁竞争风险，不建议长期这样跑）。

---

## 四、联调前置清单（缺一项就会看到 404/403/连不上）

| 依赖 | 要求 |
|---|---|
| Nacos | `127.0.0.1:8848` 已启动 |
| `sfc-core` | 已启动并注册；group=`SFC`、namespace=空串 |
| `sfc-ai-service` | 已启动（`8091`）并注册；**同上 group/namespace**，否则「注册成功但互相看不见」 |
| `sfc-gateway` | 已启动；`SFC_JWT_SECRET` 必须注入为 core 的 JWT 会话密钥（core 配置项 `sys.jwt-secret`，两侧同一值且恰好 32 字符），否则 core / 网关**启动即失败** |
| `SFC_INTERNAL_KEY` | **core 与 AI 服务必须配置同一份**：AI 服务调 core 的 `/api/internal/**` 靠它自签凭证；core 侧没配则内部接口一律 403（fail-closed） |
| 浏览器访问 | 只访问网关地址，不要直连 `8087` / `8091` |

> 启动顺序建议：Nacos → core → AI 服务 → 网关。
