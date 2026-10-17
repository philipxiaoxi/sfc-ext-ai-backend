<h1 align="center">sfc-ai-service / 咸鱼云 AI 助手微服务</h1>

<p align="center">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License" />
  </a>
</p>

<p align="center">
  WebSocket 流式对话 + 多模型/多提供商管理 —— 独立微服务形态
</p>

---

## 概述

本项目提供咸鱼云的 AI 助手后端能力：基于 **WebSocket** 的流式 AI 对话，以及 LLM 提供商/模型的 REST CRUD 管理。

> **⚠️ 形态变更（Phase 2）**：本项目原为宿主插件 `sfc-ext/sfc-ext-ai`（以 jar + `plugin-lib` 形态被 `sfc-core`
> Merge 加载，复用宿主的 Spring 容器与数据源）。现已迁为 `microservices/` 下的**独立微服务**：
>
> | | 插件期 | 现在（微服务） |
> |---|---|---|
> | 形态 | `sfc-core` 内的插件 jar | 独立进程，端口 `8091` |
> | 父 POM | `sfc-ext`（继承宿主根 pom） | `microservices`（**不继承**宿主根 pom） |
> | 宿主依赖 | `sfc-core` (provided) + 宿主实现类 | **零宿主依赖**，只用 `com.sfc.cloud:*` 共享库 |
> | 数据源 | 复用宿主的 `xyy.db` | **独立 SQLite `ai.db`** |
> | 身份来源 | 宿主 Spring Security 上下文 | 网关注入的 `X-Sfc-Uid` 信任头 |
> | 网盘操作 | 直接调宿主 `DiskFileSystem` | 经 `sfc-remote-client`（Feign）回调 `sfc-core` |
> | 对外入口 | 宿主进程 | `sfc-gateway`（网关新增 `Path=/api/ai/**` 路由） |
> | 插件元数据 | `plugin-info.json`、`config-properties.json`、`AutoConfiguration.imports` | **已删除**（去插件化） |
>
> **前端影响**见 [`docs/frontend-adaptation-checklist.md`](docs/frontend-adaptation-checklist.md)。

## 前置要求

- Java 25+、Maven 3.9+
- 咸鱼云后端仓库（本项目位于其 `microservices/sfc-ai-service` 目录下，作为**独立 git 仓库**存在）
- 运行时依赖：Nacos（`8848`）、`sfc-core`（`8087`）、`sfc-gateway`

## 构建与运行

构建（**必须从仓库根做单次 reactor 构建**，因为共享库 `sfc-common` / `sfc-common-data` 在根目录而不在本目录的 reactor 内）：

```bash
# 全量
mvn clean package -DskipTests

# 只构建微服务侧（含本服务）
mvn package -DskipTests -pl :sfc-ai-service -am
```

⚠️ 不要单独对 `microservices/pom.xml` 构建：共享库不在它的 reactor 里，会从本地仓库解析到**旧快照**。

运行：

```bash
# AI 服务调 core 的 /api/internal/** 需要与 core 同一份密钥，否则内部接口一律 403（fail-closed）
export SFC_INTERNAL_KEY='<core 的 sfc.internal.key 值>'
java -jar target/sfc-ai-service-1.0.0.jar
```

服务会监听 `8091`，并在当前工作目录创建/使用 SQLite 文件 `./ai.db`（可用
`--spring.datasource.url=jdbc:sqlite:/path/to/ai.db` 覆盖）。

启动顺序建议：**Nacos → sfc-core → sfc-ai-service → sfc-gateway**。

### WebSocket 与 REST 入口

| 能力 | 路径 |
|---|---|
| WebSocket 流式对话 | `/api/ai/wschat` |
| 对话记录 | `/api/ai/conversation/*` |
| LLM 提供商管理 | `/api/ai/provider/list\|get\|save\|delete` |
| 模型管理 | `/api/ai/model/list\|get\|save\|delete` |
| 适配器 | `/api/ai/adapter/*` |
| 查询 | `/api/ai/query/*` |
| 敏感工具调用记录 | `/api/ai/sensitiveToolCall/*` |

对外**只经网关**访问；服务本身只允许内网/本机暴露，且**不要**由前端自行发送 `X-Sfc-Uid`（那是网关注入的信任头）。

## 协议

### WebSocket 聊天

**端点：** `ws://<网关地址>/api/ai/wschat`

详细协议定义参见 [docs/websocket-protocol.md](docs/websocket-protocol.md)。

**基本流程：**

```
客户端 ──→ START_SESSION ──→ 服务端
客户端 ←── SESSION_ACK ──── 服务端

客户端 ──→ CHAT {modelId, content} ──→ 服务端
客户端 ←── TEXT "流式片段" ────────── 服务端
客户端 ←── TEXT "更多片段" ────────── 服务端
客户端 ←── DONE ───────────────────── 服务端
```

### REST API

**模型提供商管理：** `/api/ai/provider` — CRUD 管理 OpenAI/DeepSeek 等提供商（API Key、Base URL 等）

**模型管理：** `/api/ai/model` — CRUD 管理可用 AI 模型（关联提供商）

## 技术栈

| 技术 | 用途 |
|------|------|
| Java 25 + Maven 3.9+ | 开发/构建 |
| Spring Boot 4.0.8（由 `microservices` 聚合器单点声明） | 框架 |
| Spring WebSocket | 实时通信 |
| Spring AI 2.0.0 | AI 模型集成（OpenAI / DeepSeek） |
| Spring Data JPA + SQLite | 独立持久化（`ai.db`） |
| Spring Cloud Alibaba Nacos Discovery | 服务注册发现（group=`SFC`，namespace=空串） |
| Project Reactor (Flux) | 流式响应 |
| `sfc-common` / `sfc-common-data` | 仓库内共享库（信封 / 异常 / 工具 / JPA 基座 / `@SfcUID`） |
| `sfc-remote-client` | 回调 core 网盘内部接口（Feign） |

## 项目结构

```
backend
├── pom.xml                                     # Maven 构建（parent = microservices 聚合器）
├── README.md
├── LICENSE                                     # MIT
├── docs/
│   ├── websocket-protocol.md                   # WebSocket 消息协议
│   ├── ai-tool-permission-frontend-api.md      # 工具权限相关前端接口
│   └── frontend-adaptation-checklist.md        # 插件→微服务的前端适配清单
│
└── src/main
    ├── java/com/sfc/ai/
    │   ├── AiServiceApplication.java           # 微服务启动类（@SpringBootApplication）
    │   ├── AiAutoConfiguration.java            # 装配中枢（显式 @Import 清单，保持与插件期一致）
    │   ├── annotation/                          # 注解
    │   ├── config/AiWebSocketConfig.java        # WebSocket 端点注册
    │   ├── constant/                            # 消息类型等枚举
    │   ├── controller/                          # REST + WebSocket 处理器
    │   ├── core/                                # 对话执行核心（ChatRunner/AgentExecutor/SessionContext…）
    │   │   ├── adapter/                         # 模型适配器（OpenAI / DeepSeek）
    │   │   ├── advisor/                         # Spring AI Advisor
    │   │   ├── channel/                         # 消息通道（WebSocket / 其它）
    │   │   ├── memory/                          # 对话记忆（JPA 持久化）
    │   │   ├── message/                         # 消息模型
    │   │   └── tool/                            # 工具调用与审批
    │   ├── identity/                            # 身份设施（X-Sfc-Uid + CurrentUserProvider 实现）
    │   ├── model/                               # po / vo / param / chat 消息与载荷
    │   ├── repo/                                # JPA Repository（SfcBaseRepo）
    │   ├── service/ + service/impl/             # 业务服务（SfcCrudService 系列）
    │   └── tool/                                # 提供给 AI 的工具（网盘、文本搜索等）
    │
    └── resources/
        └── application.yml                      # 端口 8091 / 独立 SQLite / Nacos / 内部接口密钥
```

> 插件期的 `plugin-info.json`、`config-properties.json` 与
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
> 已随「去插件化」删除；`AiAutoConfiguration` 现由 `AiServiceApplication` 显式 `@Import`。

## 数据说明

本服务使用**自己的** SQLite 数据库 `ai.db`，与宿主的 `xyy.db` 完全分离。

⚠️ 插件期的 AI 数据（会话、消息、提供商、模型、工具调用记录）建在宿主的 `xyy.db` 中，
**不会自动迁移**到 `ai.db`，因此历史对话与已配置的模型在微服务里默认是空的。
如需保留，请在部署时手工搬迁对应表，或临时把数据源指向旧库（注意 SQLite 多进程写入的锁竞争）。

## License

[MIT](LICENSE) © philipxiaoxi
