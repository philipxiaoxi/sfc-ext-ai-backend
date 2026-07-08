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

该项目为 sfc-ext-ai 插件的后端模块，作为咸鱼云平台的一个扩展插件（jar），提供基于 SSE（Server-Sent Events）的流式 AI 对话能力。

该项目的开发与构建需要基于 [咸鱼云网盘后端](https://github.com/mjt233/saltedfishcloud-backend) 项目下。

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
    ├── java/com/sfc/ai
    │   ├── AiAssistantAutoConfiguration.java    # 自动配置
    │   ├── controller/AiAssistantController.java # SSE 聊天接口
    │   └── model/ChatRequest.java               # 请求体
    └── resources
        ├── META-INF/spring/                      # 自动配置注册
        └── plugin-info.json                      # 插件元信息
```

## License

[MIT](LICENSE) © philipxiaoxi
