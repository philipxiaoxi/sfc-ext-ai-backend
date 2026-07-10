# 如何理解 Java/Spring Boot 的 LLM 项目设计

> 面向 Node.js/Python 开发者的上手指南

---

## 复杂感从哪来

### Node.js/Python 的 LLM 框架：命令式

```python
# Python: 按顺序写，每一步都在眼前
memory = ChatMemory()
history = memory.load(session_id)
prompt = build_prompt(history, user_msg)
reply = await llm.chat(prompt)
memory.save(session_id, user_msg, reply)
```

代码就是执行流程，直来直去。

### Java/Spring Boot：声明式 + 自动装配

```java
// Java: 先搭积木，框架帮你串联
ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new InMemoryChatMemoryRepository())
    .build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
    .build();

// 实际调用只有一行，中间件自动生效
chatClient.prompt().user(content).stream().chatResponse();
```

**复杂感来自：** 用 Node.js 写 10 行代码能跑通的事，Java 要写 30 行——因为 Java 不是给你写"一次性脚本"的，它在搭一个**可维护、可扩展的工程体系**。

---

## 上手最快的心智模型

记住一句话：

> **Spring Boot 开发 = 搭积木。先把积木搭好，再按按钮让它跑。**

对应到本项目：

| 步骤 | 你在做什么 | Node.js 类比 |
|---|---|---|
| `@Bean` / `@Service` | 声明"我这里有个积木" | `module.exports = new Xxx()` |
| 构造函数注入 | 把积木 A 插到积木 B 上 | `new B(new A())` |
| `Advisor` | 给流程加中间件 | `app.use(middleware)` |
| `builder().build()` | 把一组积木拼成成品 | 配置对象 `{ a, b, c }` |

不需要记住每一块积木怎么造，只需要知道：
1. **项目里有哪些积木**（看 `AiAutoConfiguration.java` —— 它是"积木清单"）
2. **积木之间怎么插**（看构造函数参数）
3. **按哪个按钮触发**（看 handler 里的 `chatClient.prompt()`）

---

## 具体到当前项目，三步上手

```
┌─────────────────────────────────────────────────┐
│  1️⃣ 积木清单 → AiAutoConfiguration.java        │
│                                                 │
│  翻这个文件 = 看项目的 IoC 容器里注册了什么       │
│  比看 20 个 service 文件快得多                   │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│  2️⃣ 积木拼接 → 看构造函数的参数                  │
│                                                 │
│  比如 ChatClientService 构造要 memoryRepo，      │
│  说明它依赖 InMemoryChatMemoryRepository         │
│  改一条链路就知道从哪里下手                       │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│  3️⃣ 业务入口 → AiChatSocketHandler              │
│                                                 │
│  所有业务逻辑的"触发点"都在这里                   │
│  看 handleTextMessage → 分派到 handleChat        │
│  → 调 ChatClientService → 调 ChatClient          │
│  → 中间件自动处理上下文                            │
└─────────────────────────────────────────────────┘
```

不需要从头读到尾。想改上下文窗口大小？直接去 `ChatClientService` 改 builder 链。想加清理逻辑？去 `AiChatSocketHandler.afterConnectionClosed`。知道积木在哪，改一把螺丝刀的事。

---

## 对比总结

| 维度 | Node.js / Python | Java / Spring Boot |
|---|---|---|
| 代码风格 | 命令式，流程直观 | 声明式，搭积木再运行 |
| 上手速度 | 快（10 行跑通） | 慢（30 行起步） |
| 修改维护 | 改逻辑需通读全文 | 改逻辑找固定位置 |
| 适用场景 | 原型、小工具 | 长期维护的企业项目 |

**一句话：Node.js 写 LLM 应用像写菜谱（步骤明确），Java/Spring Boot 像搭宜家家具（先拼框架再使用）。看着复杂，但改起来爽——所有东西都有固定位置，不需要猜变量从哪来。**
