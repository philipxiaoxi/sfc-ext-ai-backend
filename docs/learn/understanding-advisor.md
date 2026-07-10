# 理解 Spring AI 的 Advisor

> 为什么 Java 设计感觉比 Python/Node.js 复杂？

---

## Advisor 是什么

**Advisor = 中间件。** 在 LLM 调用的前后自动插入逻辑。

### Node.js 类比

```js
// Express 中间件
app.use(loadHistory);   // before: 调用前加载历史
app.use(saveHistory);   // after:  调用后保存历史
app.post('/chat', handler);
```

```java
// Spring AI Advisor
ChatClient.builder(chatModel)
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(memory)  // before+after 自动生效
    )
    .build();
```

---

## 为什么感觉比 Python/Node.js 复杂

**Python/Node.js 是露出的，Java 是包住的。**

```python
# Python: 一眼看到所有细节
memory.chat_memory.add_user_message("你好")
memory.chat_memory.add_ai_message(reply)
```

```java
// Java: 只看到 builder，里面干了什么？不知道
ChatClient.builder(chatModel)
    .defaultAdvisors(new MessageChatMemoryAdvisor(memory))
    .build();

chatClient.prompt().user("你好").stream();
// ^ 谁帮你存消息了？Advisor。Advisor 怎么存的？需要看源码。
```

**Python/Node.js 允许渐进式理解：** 先调 `chat()` 跑通，再慢慢加 memory、加 tool。每一步都在增加认知，不是一次性全盘接受。

**Java 的 builder + 自动装配 + 中间件 三重抽象叠在一起，不看源码不知道默默帮你做了什么。**

---

## 但这不是"Java 不行"，是框架定位不同

| 维度 | Python/Node.js | Java/Spring Boot |
|---|---|---|
| 设计哲学 | 透明可控 | 自动化工程 |
| 上手路径 | 从简到繁渐近 | 搭好框架再运行 |
| 理解成本 | 写在明面上 | 需猜背后逻辑 |
| 适用场景 | 原型、快速迭代 | 长期维护的企业项目 |

Spring AI 的 `Advisor` 是典型——它替你干了"加载历史→发请求→存历史"三件事。你不用写，但**不看源码就不知道它干了这三件事**。

---

## 实用建议

不需要了解所有底层封装，只抓一个原则：

> **看 `xxxAutoConfiguration.java` 和 `xxxAdvisor.java`，不看 builder 链。**

- `AutoConfiguration` 告诉你注册了什么组件
- `Advisor` 告诉你在调用前后插了什么逻辑

比如打开 `MessageChatMemoryAdvisor.java`，核心就两个方法：

```
before() → memory.get(conversationId)  → 加载历史
after()  → memory.add(conversationId)  → 保存历史
```

看完就破案了，不需要钻 builder 链。

---

## 承认

Spring AI 在这点上确实做得不够好——文档没有把这层抽象讲透，导致新手看到 builder 链以为要理解每一环才能用。实际上只需要理解 **Advisor = 中间件** 这一个心智模型，就能推演出整个流程。
