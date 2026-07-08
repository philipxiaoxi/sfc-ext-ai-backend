<h1 align="center">sfc-ext-ai-assistant / backend</h1>

<p align="center">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License" />
  </a>
</p>

<p align="center">
  咸鱼云 AI 助手后端插件 — Spring Boot 自动配置 + SSE 流式对话接口
</p>

---

## 概述

backend 是 [sfc-ext-ai-assistant](https://github.com/philipxiaoxi/sfc-ext-ai-assistant) 的后端模块，作为咸鱼云平台的一个扩展插件（jar），提供基于 SSE（Server-Sent Events）的流式 AI 对话能力。

## 前置要求

- Java 25+
- Maven 3.9+
- 咸鱼云 3.1.2+

## 构建

```bash
mvn package -DskipTests
```

产物位于 `target/sfc-ext-ai-assistant-1.0.0.jar`。

## 安装

将 jar 放入咸鱼云的 `plugins/` 目录，重启服务即可。

## API

### POST /api/ai-assistant/chat

请求体：

```json
{ "message": "你好" }
```

响应为 SSE 流，每行一个十进制 Unicode 码点，以 `[DONE]` 标记结束。

## 项目结构

```
backend
├── pom.xml
└── src/main
    ├── java/com/sfc/aiassistant
    │   ├── AiAssistantAutoConfiguration.java    # 自动配置
    │   ├── controller/AiAssistantController.java # SSE 聊天接口
    │   └── model/ChatRequest.java               # 请求体
    └── resources
        ├── META-INF/spring/                      # 自动配置注册
        └── plugin-info.json                      # 插件元信息
```

## License

[MIT](LICENSE) © philipxiaoxi
