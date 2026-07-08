package com.sfc.ai.controller;

import com.sfc.ai.model.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.*;

/**
 * AI 助手对话接口控制器。
 * 提供基于 SSE（Server-Sent Events）的流式对话能力。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-assistant")
public class AiAssistantController {

    /**
     * SSE 逐字发送线程池。
     * <p>
     * 采用 CallerRunsPolicy 拒绝策略：当线程池饱和时由调用线程执行，
     * 避免任务被静默丢弃导致 emitter 干挂、客户端收不到任何数据。
     * 每个连接会长时间占用线程（逐字发送），因此队列不宜过大，
     * 且线程存活时保持核心线程以应对持续请求。
     */
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            4,
            32,
            60, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "ai-assistant-sse");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 流式对话接口。
     * 逐字返回固定的 Markdown 演示内容，模拟大模型流式输出效果，结束时发送 [DONE] 标记。
     *
     * @param request 对话请求体，包含用户输入的消息
     * @return SSE 发射器，持续推送消息，结束时推送 [DONE]
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(120000L);

        String reply = """
                ##   👋 你好，我是咸鱼云 AI 助手！
                
                很高兴为你服务！我目前处于**内测阶段**，这是一个流式返回的演示消息。
                
                ### 🚀 我能做什么？
                
                - 📂 **文件管理**：帮你查找、整理网盘中的文件
                - 🔍 **智能搜索**：通过自然语言快速定位你想要的内容
                - 📊 **信息汇总**：对文档内容进行摘要和分析
                - 🛠️ **快捷操作**：一键执行常用任务
                
                ### 📌 使用提示
                
                > 当前为演示版本，后端返回的是固定 Markdown 内容。
                > 正式版将接入 AI 大模型，支持真正的对话交互。
                
                你可以尝试输入任意文字，我会逐字打印这段 Markdown，
                前端使用 **markdown-it** 进行渲染，支持标题、列表、引用等格式。
                
                ---
                
                😊 敬请期待更多功能上线！
                """;

        emitter.onCompletion(() -> log.info("AI 助手 SSE 连接结束"));
        emitter.onTimeout(() -> {
            log.info("AI 助手 SSE 连接超时");
            emitter.complete();
        });
        emitter.onError(e -> {
            log.error("AI 助手 SSE 连接异常 -> {}", e.getMessage(), e);
            emitter.complete();
        });

        sseExecutor.execute(() -> {
            try {
                reply.codePoints().forEach(cp -> {
                    try {
                        emitter.send(String.valueOf(cp), MediaType.TEXT_PLAIN);
                        Thread.sleep(20);
                    } catch (IOException ioe) {
                        throw new CompletionException(ioe);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                });
                emitter.send("[DONE]", MediaType.TEXT_PLAIN);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
