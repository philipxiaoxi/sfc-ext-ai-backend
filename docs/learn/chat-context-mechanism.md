# 聊天上下文管理机制详解

> 面向 Node.js/前端开发者的 Spring Boot 解释

---

## 先理解 Spring Boot 的"魔法"

Spring Boot 有个核心概念叫 **IoC 容器**（控制反转）。可以想象成一个全局的 **Map（对象池）**：

- 用 `@Component`、`@Service`、`@Bean` 等注解标记一个类，告诉框架"这个类交给你管了"
- Spring Boot 启动时扫描这些注解，自动 new 出实例，放进这个 Map
- 其他地方要用的时候，写 `@Autowired` 或构造函数参数，Spring 自动从 Map 里取出来注入进去

```js
// Node.js 类比
// @Service 相当于
const container = new Map();
container.set('UserService', new UserService());

// @Autowired 相当于
class UserController {
  constructor() {
    this.userService = container.get('UserService'); // 自动注入
  }
}
```

---

## 聊天上下文管理的完整流程

### 第 1 步：启动时——注册内存仓库

文件 `AiAutoConfiguration.java`：

```java
@Import({ InMemoryChatMemoryRepository.class })
```

这行代码让 Spring Boot 在启动时 new 一个 `InMemoryChatMemoryRepository` 放进容器。

用 Node.js 理解它的内部实现：

```js
class InMemoryChatMemoryRepository {
  store = new Map(); // key = conversationId, value = 消息数组

  getMessages(conversationId) {
    return this.store.get(conversationId) || [];
  }

  addMessage(conversationId, message) {
    if (!this.store.has(conversationId)) {
      this.store.set(conversationId, []);
    }
    this.store.get(conversationId).push(message);
  }

  clear(conversationId) {
    this.store.delete(conversationId);
  }
}
```

### 第 2 步：启动时——构建 ChatClientService

`ChatClientService.java` 的构造函数：

```java
public ChatClientService(
    ChatMemoryRepository chatMemoryRepository, // ← Spring 自动注入上面那个内存仓库
    List<LlmProvider> providers,
    List<LlmModel> models
) {
    this.chatMemoryRepository = chatMemoryRepository;
    // ...
}
```

Spring 看到 `ChatMemoryRepository` 参数，去容器里找，找到了之前注册的 `InMemoryChatMemoryRepository`，自动传进来。

### 第 3 步：用户连接 WebSocket

客户端连 `ws://host/api/ai/wschat`。

`AiChatSocketHandler.java` 中，`afterConnectionEstablished` 被触发，类似于前端 WebSocket 的 `onopen`：

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    // session 是 WebSocket 连接对象
    // 类似前端的 ws 实例，可以挂载自定义属性
}
```

### 第 4 步：客户端发送 START_SESSION

客户端发：

```json
{"type": "START_SESSION", "data": {"sessionId": ""}}
```

服务端收到后：

```java
String sessionId = UUID.randomUUID().toString(); // 生成唯一 ID
session.getAttributes().put("SESSION_ID", sessionId);
// 相当于: ws.SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";
```

然后服务端回复 `SESSION_ACK`，把 sessionId 返回给客户端。

**Node.js 类比：**

```js
// 服务端收到 START_SESSION
onMessage((msg) => {
  if (msg.type === 'START_SESSION') {
    const sessionId = crypto.randomUUID();
    ws.sessionId = sessionId; // 挂到连接对象上
    ws.send(JSON.stringify({
      type: 'SESSION_ACK',
      data: { sessionId }
    }));
  }
});
```

### 第 5 步：客户端发送 CHAT（关键步骤）

客户端发：

```json
{"type": "CHAT", "data": {"modelId": 1, "content": "你好"}}
```

服务端的 `handleChat` 方法被调用，它做 4 件事：

#### 5.1 从 WebSocket 连接上取出 sessionId

```java
String sessionId = (String) session.getAttributes().get("SESSION_ID");
// 相当于: const sessionId = ws.SESSION_ID;
```

#### 5.2 查数据库找到模型和提供商

```java
LlmProvider provider = llmProviderService.findById(model.getProviderId());
// 查到 API Key、Base URL 等
```

#### 5.3 调用 ChatClientService 创建 ChatClient

```java
ChatClient chatClient = chatClientService.getChatClient(provider, model, sessionId);
```

#### 5.4 用 ChatClient 发消息给 LLM，返回流式响应

```java
chatClient
  .prompt()                         // 构造 prompt
  .user(userRequest.getContent())   // 设置用户消息
  .stream()                         // 流式返回
  .chatResponse()                   // 得到 Flux<ChatResponse>
  .subscribe(response -> {
    // 把 AI 回复的文本通过 WebSocket 推给客户端
  });
```

### 第 6 步：ChatClientService.getChatClient 内部——上下文注入点

这是最核心的一步，展开说：

```java
public ChatClient getChatClient(LlmProvider provider, LlmModel model, String conversationId) {
    // 1. 根据提供商信息（API Key, Base URL）创建 AI 模型客户端
    OpenAiChatModel chatModel = OpenAiChatModel.builder()
        .apiKey(provider.getApiKey())
        .baseUrl(provider.getBaseUrl())
        .model(model.getModelName())
        .build();

    // 2. 创建消息窗口内存（限定只保留最近 20 条）
    ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository) // 使用第 1 步的内存仓库
        .build(); // 默认 maxMessages = 20

    // 3. 创建 ChatClient，把以上组件组装起来
    return ChatClient.builder(chatModel)
        .defaultAdvisors(
            new MessageChatMemoryAdvisor(chatMemory) // ← 上下文的核心！
        )
        .build();
}
```

**`MessageChatMemoryAdvisor` 是什么？**

可以把它想象成 **Express 的中间件**（middleware）：

```js
// Node.js 类比
app.post('/chat',
  loadHistoryMiddleware,  // ★ 自动加载历史上下文
  async (req, res) => {
    const reply = await callLLM(req.prompt);
    return reply;
  },
  saveHistoryMiddleware   // ★ 自动保存当前对话
);
```

`MessageChatMemoryAdvisor` 在每次 `chatClient.prompt().stream()` 调用时，自动做两件事：

1. **执行前（before）**：根据 `conversationId` 从 `InMemoryChatMemoryRepository` 中取出历史消息，合并到当前 prompt 里一起发给 LLM
2. **执行后（after）**：LLM 返回响应后，把用户消息和 AI 回复追加到 `InMemoryChatMemoryRepository` 中

整个过程对调用方透明，不需要手动"加载历史"或"保存历史"。

### 第 7 步：Memory 内部——窗口裁剪

`MessageWindowChatMemory` 收到新消息后，检查总条数是否超过 20。如果超过，执行滑动窗口裁剪：

```
原始: [旧旧旧旧旧旧旧旧旧旧旧旧旧旧旧旧旧旧旧新新] → 22 条
裁剪: [              旧旧旧旧旧旧旧旧旧旧旧旧新新] → 20 条
```

裁剪规则：
- 最旧的消息被丢弃
- 如果最旧的消息是 ASSISTANT 回复，继续往前删直到从 USER 消息开头（保证不出现孤立的 AI 回复）

---

## 完整流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│ 启动时 Spring 容器                                                  │
│   new InMemoryChatMemoryRepository()  ← 就是个 Map                  │
│   new ChatClientService(memoryRepo)                                 │
│   new AiChatSocketHandler(chatClientService)                        │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
客户端连 WebSocket ────────┤
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 1️⃣  START_SESSION                                                   │
│    生成 sessionId = UUID                                            │
│    挂在 ws.SESSION_ID 上                                            │
│    回复 SESSION_ACK                                                  │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
客户端发 CHAT ────────────┤
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 2️⃣  handleChat()                                                    │
│    a) 拿 sessionId                                                  │
│    b) 查数据库 → 得到 provider(API Key) + model                      │
│    c) 调 getChatClient(provider, model, sessionId)                  │
│       → OpenAiChatModel（真正的 HTTP 客户端，调 OpenAI API）          │
│       → MessageWindowChatMemory（内存，限制 20 条）                  │
│       → MessageChatMemoryAdvisor（中间件）                           │
│    d) .prompt().user(content).stream()                               │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 3️⃣  MessageChatMemoryAdvisor（中间件）自动执行                        │
│    BEFORE:                                                          │
│      memory.get(sessionId) → 取出历史消息 []                        │
│      合并到 prompt（如果是第 1 次，历史为空）                        │
│                                                                     │
│    发请求 → OpenAi API（流式返回）                                   │
│                                                                     │
│    AFTER:                                                           │
│      memory.add(sessionId, userMessage)    ← 存用户消息              │
│      memory.add(sessionId, assistantReply) ← 存 AI 回复              │
│      MessageWindowChatMemory 检查是否超过 20 条                      │
│        超过 → 滑动裁剪，丢弃最旧的                                   │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 4️⃣  流式返回给前端                                                   │
│    TEXT "你"                                                         │
│    TEXT "好"                                                         │
│    TEXT "！"                                                         │
│    DONE                                                              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Node.js 开发者友好对照表

| Spring Boot 概念 | Node.js 类比 |
|---|---|
| `@Service` / `@Component` | 自动注册到全局 Map 的类 |
| `@Autowired` / 构造函数注入 | 依赖自动传入，类似 DI 容器 |
| `InMemoryChatMemoryRepository` | `new Map()`，手动管理 |
| `MessageChatMemoryAdvisor` | Express/koa 中间件（before/after hook） |
| `MessageWindowChatMemory` | 一个自动裁剪的滑动窗口数组 |
| `ChatClient.prompt().user().stream()` | 类似 `await openai.chat.completions.create({stream: true})` |
| `Flux<ChatResponse>` | Observable / ReadableStream |
| `session.getAttributes()` | 给 `ws` 对象挂自定义属性 |

---

## 存在问题

`afterConnectionClosed` 没有清理内存，相当于：

```js
// 当前代码
ws.onclose = () => {
  console.log('连接关闭');
  // 没有做任何清理！
};

// 应该加的逻辑
ws.onclose = () => {
  chatMemoryStore.delete(ws.sessionId); // 清理上下文
};
```

频繁创建新会话时，Map 里的条目只增不减，存在内存泄漏。
