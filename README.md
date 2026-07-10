<h1 align="center">sfc-ext-ai-assistant / backend</h1>

<p align="center">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License" />
  </a>
</p>

<p align="center">
  咸鱼云 AI 助手后端插件 — WebSocket 流式对话 + 多模型/多提供商管理
</p>

---

## 概述

该项目为 sfc-ext-ai 插件的后端模块，作为咸鱼云平台的一个扩展插件（jar），提供基于 **WebSocket** 的流式 AI 对话能力，以及提供商/模型的 REST CRUD 管理接口。

开发与构建需要基于 [咸鱼云网盘后端](https://github.com/mjt233/saltedfishcloud-backend) 项目。

## 前置要求

- Java 25+
- Maven 3.9+
- 咸鱼云 3.1.2+

## 配置开发与构建环境

1. 拉取咸鱼云网盘后端项目
   ```bash
   git clone https://github.com/mjt233/saltedfishcloud-backend.git
   ```

2. 在咸鱼云网盘后端的 `sfc-ext` 目录下拉取本项目到 `sfc-ext-ai`
   ```bash
   cd saltedfishcloud-backend/sfc-ext &&
   git clone https://github.com/philipxiaoxi/sfc-ext-ai-backend.git sfc-ext-ai
   ```

3. 设置 Maven profile 为 `develop`，或指定 SpringBoot 配置文件为 `sfc-core/src/main/config/application-develop.yml`

4. 修改 `application-develop.yml`，在 `plugin.extra-resource` 中添加一项：
   ```yaml
   plugin:
     extra-resource:
       - sfc-ext/sfc-ext-ai
   ```

5. 如果插件存在第三方依赖，请在 `sfc-ext/sfc-ext-ai` 目录执行 `mvn compile` 确保依赖库得到加载，然后在 IDE 中刷新依赖信息以便后续能使用 `build_project` 进行验证。

6. 打包构建：在 `sfc-ext` 目录执行
   ```bash
   mvn clean package
   ```
   构建完成后，插件 jar 包会生成在 `release/ext-available/` 目录下。

7. 安装：将 jar 包复制到咸鱼云程序运行路径下的 `ext/`，重启服务即可。


## 协议

### WebSocket 聊天

**端点：** `ws://<host>/api/ai/wschat`

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

**模型提供商管理：** `/api/ai/provider` — CRUD 管理 OpenAI/Anthropic 等提供商（API Key、Base URL 等）

**模型管理：** `/api/ai/model` — CRUD 管理可用 AI 模型（关联提供商）

## 技术栈

| 技术 | 用途 |
|------|------|
| Java 25 + Maven 3.9+ | 开发/构建 |
| Spring Boot + WebSocket | 框架 + 实时通信 |
| Spring AI 2.0.0 | AI 模型集成（OpenAI 协议） |
| Spring Data JPA | ORM 持久化 |
| Project Reactor (Flux) | 流式响应 |
| 咸鱼云 sfc-core (provided) | 基础平台依赖 |

## 项目结构

```
backend
├── pom.xml                                           # Maven 构建（Spring AI 2.0.0）
├── README.md
├── LICENSE                                           # MIT
├── docs/
│   └── websocket-protocol.md                         # WebSocket 消息协议文档
│
└── src/main
    ├── java/com/sfc/ai/
    │   ├── AiAutoConfiguration.java                  # 自动配置入口
    │   ├── config/
    │   │   └── AiWebSocketConfig.java                # WebSocket 端点注册
    │   ├── controller/
    │   │   ├── AiChatSocketHandler.java              # WebSocket 聊天处理器（核心）
    │   │   ├── LlmProviderController.java            # 提供商 REST CRUD
    │   │   └── LlmModelController.java               # 模型 REST CRUD
    │   ├── service/
    │   │   ├── ChatClientService.java                # Spring AI ChatClient 构造
    │   │   ├── LlmProviderService.java
    │   │   ├── LlmModelService.java
    │   │   └── impl/
    │   │       ├── LlmProviderServiceImpl.java
    │   │       └── LlmModelServiceImpl.java
    │   ├── repo/
    │   │   ├── LlmProviderRepo.java                  # JPA Repository
    │   │   └── LlmModelRepo.java
    │   ├── model/
    │   │   ├── po/
    │   │   │   ├── LlmProvider.java                  # 提供商实体
    │   │   │   └── LlmModel.java                     # 模型实体
    │   │   └── chat/
    │   │       ├── message/
    │   │       │   ├── UserRequest.java              # WebSocket 请求体
    │   │       │   └── LlmResponse.java              # WebSocket 响应体
    │   │       └── payload/
    │   │           ├── ChatPayload.java
    │   │           ├── StartSessionPayload.java
    │   │           ├── SessionAckPayload.java
    │   │           ├── TextPayload.java
    │   │           ├── ErrorPayload.java
    │   │           └── DonePayload.java
    │   └── constant/
    │       ├── UserMessageType.java                  # 客户端消息类型枚举
    │       └── LlmMessageType.java                   # 服务端消息类型枚举
    │
    └── resources/
        ├── application.yml                           # 日志配置
        ├── plugin-info.json                          # 插件元信息
        ├── config-properties.json                    # 管理后台配置面板
        └── META-INF/spring/
            └── AutoConfiguration.imports             # Spring Boot 自动配置注册
```

## License

[MIT](LICENSE) © philipxiaoxi
