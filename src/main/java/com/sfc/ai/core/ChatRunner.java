package com.sfc.ai.core;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.tool.FallbackToolCallbackResolver;
import com.sfc.ai.core.tool.SfcToolCallingManager;
import com.sfc.ai.core.tool.ToolProvider;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.payload.ChatPayload;
import com.sfc.ai.model.chat.payload.DonePayload;
import com.sfc.ai.model.chat.payload.TextPayload;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CHAT 请求执行器。
 * <p>
 * 无状态 Spring Bean，承担一次 CHAT 请求的全流程：解析模型、构造 ChatClient、
 * 触发首聊标题副作用、订阅 LLM 流式响应并通过 {@link MessageChannel} 下发。
 * 调用方负责提供单连接的有状态上下文（{@link SessionContext}、{@link ToolExecutionManager}、
 * 已注册的客户端中介工具 Map）。本类不持有任何 per-conn 状态。
 */
@Slf4j
@RequiredArgsConstructor
public class ChatRunner {

    private static final String SYSTEM_PROMPT_PREFIX =
            "你的咸鱼云网盘 AI 助手，可以帮助用户整理网盘、查找文件。当前用户用户名为: ";

    private final LlmResolver llmResolver;
    private final ChatClientService chatClientService;
    private final ToolProvider toolProvider;
    private final ConversationTitleGenerator titleGenerator;

    /**
     * 执行一次 CHAT 请求的全流程。
     * <p>
     * 调用方必须保证传入的 {@code sessionContext} 已经存在会话且未停止。
     *
     * @param chatData              客户端 CHAT payload
     * @param channel               下行消息通道
     * @param sessionContext        单连接会话状态
     * @param toolExecutionManager  工具执行管理（用于构造 {@link SfcToolCallingManager}）
     * @param registeredTools       当前连接已注册的客户端中介工具集合（只读）
     * @param config                Agent 执行器配置（包含 {@code autoGenerateTitle} 等开关）
     */
    public void run(ChatPayload chatData,
                    MessageChannel channel,
                    SessionContext sessionContext,
                    ToolExecutionManager toolExecutionManager,
                    Map<String, ToolCallback> registeredTools,
                    AgentExecutorConfig config) {

        // 1. 解析模型（校验失败抛 JsonException，由 AgentExecutor 捕获发 error）
        LlmResolver.LlmContext ctx = llmResolver.resolve(
                chatData.getModelId(), sessionContext.getUser());

        // 2. 首聊标题副作用（先记录 isFirstChat，再 mark，再 fire；保留原 handleChat 顺序）
        boolean isFirstChat = sessionContext.isFirstChat();
        sessionContext.markFirstChatDone();
        if (isFirstChat) {
            titleGenerator.fireIfNew(channel, sessionContext, chatData.getContent(),
                    ctx, config.isAutoGenerateTitle());
        }

        // 3. 计时起点（与原 handleChat 一致：adapter 解析后即捕获，覆盖工具聚合+ChatClient 构造+订阅响应全过程）
        long startTime = System.currentTimeMillis();

        // 4. 工具聚合 + ToolCallingManager
        UserPrincipal currentUser = sessionContext.getUser();
        List<ToolCallback> allTools = new ArrayList<>();
        allTools.addAll(registeredTools.values());
        allTools.addAll(toolProvider.allToolCallbacks());
        SfcToolCallingManager sfcToolCallingManager = new SfcToolCallingManager(
                new FallbackToolCallbackResolver(),
                DefaultToolExecutionExceptionProcessor.builder().build(),
                toolExecutionManager, channel, currentUser);

        // 5. ChatClient 构造（与原 handleChat 一致）
        ChatClient chatClient = chatClientService.getChatClient(
                ctx.provider(), ctx.model(), sessionContext.getConversationId(), ctx.adapter(),
                sfcToolCallingManager,
                builder -> builder.defaultTools(allTools.toArray()));

        sessionContext.startChat();

        // 6. 订阅流（与原 AgentExecutor.handleChat 顺序 1:1 平移，不重排算子）
        sessionContext.setDisposable(chatClient.prompt(Prompt.builder()
                        .messages(SystemMessage.builder()
                                .text(SYSTEM_PROMPT_PREFIX + sessionContext.getUser().getUsername())
                                .build())
                        .build())
                .user(chatData.getContent())
                .stream()
                .chatResponse()
                .filter(msg -> !msg.getResults().isEmpty())
                .flatMap(msg -> Flux.fromStream(msg.getResults().stream()))
                .map(msg -> toLlmResponse(ctx.adapter(), msg))
                .filter(ChatRunner::hasContent)
                .doOnError(throwable -> {
                    if (sessionContext.isStopped()) {
                        return;
                    }
                    channel.sendError("LLM 响应流处理过程中出错: " + throwable.getMessage());
                    log.error("ai 消息发送出错", throwable);
                })
                .doOnComplete(() -> {
                    if (sessionContext.trySendDone()) {
                        DonePayload donePayload = new DonePayload();
                        donePayload.setReason("已完成");
                        donePayload.setModelId(ctx.model().getModelId());
                        donePayload.setTime(System.currentTimeMillis() - startTime);
                        channel.send(LlmMessageType.DONE, donePayload);
                    }
                })
                .subscribe(response -> channel.send(response.getType(), response.getData())));
    }

    /**
     * 将一条流式响应消息转换为 {@link LlmResponse}，含正文与 reasoning 内容。
     *
     * @param adapter 当前 LLM 适配器，用于提取 reasoning 内容
     * @param msg     流式响应中单条 Generation（含 AssistantMessage 输出）
     * @return 封装为 {@code LlmMessageType.TEXT} 的 LlmResponse
     */
    private static LlmResponse toLlmResponse(LlmChatAdapter adapter, Generation msg) {
        String text = msg.getOutput().getText();
        String reasoningContent = adapter.extractReasoningContent(msg.getOutput());
        TextPayload textPayload = new TextPayload();
        textPayload.setContent(text);
        textPayload.setReasoningContent(reasoningContent);
        LlmResponse llmResp = new LlmResponse();
        llmResp.setType(LlmMessageType.TEXT);
        llmResp.setData(textPayload);
        return llmResp;
    }

    /**
     * 过滤掉既无正文又无 reasoning 的响应。
     *
     * @param response 待过滤的 LlmResponse
     * @return true 表示响应有内容可继续下发；false 表示应丢弃
     */
    private static boolean hasContent(LlmResponse response) {
        TextPayload data = (TextPayload) response.getData();
        return StringUtils.hasText(data.getContent())
                || StringUtils.hasText(data.getReasoningContent());
    }
}