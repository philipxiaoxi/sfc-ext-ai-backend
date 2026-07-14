package com.sfc.ai.core;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.advisor.ToolCallNotifyAdvisor;
import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.message.UserRequest;
import com.sfc.ai.model.chat.payload.*;
import com.sfc.ai.model.chat.session.ChatSession;
import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.service.AiConversationService;
import com.sfc.ai.service.LlmModelService;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import com.sfc.ai.core.tool.ChannelMediatedToolCallback;
import com.sfc.ai.core.tool.RegisteredTool;
import com.sfc.ai.model.chat.payload.RegisterToolPayload;
import com.sfc.ai.model.chat.payload.ToolCallAckPayload;
import com.sfc.ai.tool.CommonTools;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent 执行器默认实现。
 * <p>
 * 负责 LLM 调用、流式响应处理、工具调用追踪等 Agent 核心逻辑。
 * 持有 {@link MessageChannel} 引用用于消息收发，内部管理 {@link ChatSession} 会话状态。
 * <p>
 * 在构造函数中通过 {@link MessageChannel#onMessage(MessageChannel.MessageHandler)}
 * 注册自身为入站消息处理器。
 */
@Slf4j
public class AgentExecutor {

    private final MessageChannel channel;
    private final LlmModelService llmModelService;
    private final ChatClientService chatClientService;
    private final LlmProviderService llmProviderService;
    private final LlmChatAdapterRegistry adapterRegistry;
    private final AiConversationService aiConversationService;
    private final CommonTools commonTools;

    private final Map<String, ChannelMediatedToolCallback> registeredTools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingToolCalls = new ConcurrentHashMap<>();

    private ChatSession chatSession;
    private Disposable chatDisposable;
    private CompletableFuture<?> titleFuture;

    public AgentExecutor(MessageChannel channel,
                         LlmModelService llmModelService,
                         ChatClientService chatClientService,
                         LlmProviderService llmProviderService,
                         LlmChatAdapterRegistry adapterRegistry,
                         AiConversationService aiConversationService,
                         CommonTools commonTools) {
        this.channel = channel;
        this.llmModelService = llmModelService;
        this.chatClientService = chatClientService;
        this.llmProviderService = llmProviderService;
        this.adapterRegistry = adapterRegistry;
        this.aiConversationService = aiConversationService;
        this.commonTools = commonTools;
        channel.onMessage(this::dispatch);
        channel.onClose(this::onChannelClosed);
    }

    /**
     * 通道关闭回调，取消所有进行中的操作。
     */
    private void onChannelClosed() {
        log.debug("消息通道已关闭，取消所有进行中的 AI 操作");
        if (chatDisposable != null && !chatDisposable.isDisposed()) {
            chatDisposable.dispose();
        }
        if (titleFuture != null && !titleFuture.isDone()) {
            titleFuture.cancel(true);
        }
        pendingToolCalls.values().forEach(f -> f.cancel(true));
        pendingToolCalls.clear();
        chatSession = null;
    }

    private void dispatch(UserRequest request) {
        try {
            if (request.getType() == null) {
                channel.sendError("消息类型不能为空");
                return;
            }
            switch (request.getType()) {
                case START_SESSION -> handleStartSession(request);
                case CHAT -> handleChat(request);
                case TOOL_ACK -> handleToolAck(request);
                case STOP -> handleStop();
                case REGISTER_TOOL -> handleRegisterTool(request);
                default -> channel.sendError("未知消息类型: " + request.getType());
            }
        } catch (Throwable e) {
            channel.sendError("处理消息时发生错误: " + e.getMessage());
            log.error("处理 AI 消息时发生错误", e);
        }
    }

    private void handleStartSession(UserRequest request) {
        StartSessionPayload startPayload = MapperHolder.mapper.convertValue(
                request.getData(), StartSessionPayload.class);
        String sessionId = startPayload.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        boolean isNew = !aiConversationService.existsByConversationId(sessionId);
        chatSession = new ChatSession(sessionId, channel.getUser(), isNew);
        channel.send(LlmMessageType.SESSION_ACK, new SessionAckPayload(sessionId));
    }

    private void handleChat(UserRequest request) throws Exception {
        if (chatSession == null) {
            channel.sendError("请先发送 START_SESSION 消息开启会话");
            return;
        }

        ChatPayload chatData = MapperHolder.mapper.convertValue(request.getData(), ChatPayload.class);
        if (chatData.getModelId() == null || chatData.getContent() == null) {
            channel.sendError("CHAT 消息缺少 modelId 或 content");
            return;
        }

        LlmModel model = llmModelService.findById(chatData.getModelId());
        if (model == null) {
            channel.sendError("模型不存在");
            return;
        }

        Long uid = model.getUid();
        if (uid != null && uid != 0 && (chatSession.getUser() == null || !uid.equals(chatSession.getUser().getId()))) {
            channel.sendError("无权访问该模型");
            return;
        }

        LlmProvider provider = llmProviderService.findById(model.getLlmProviderId());
        if (provider == null) {
            channel.sendError("模型提供商不存在");
            return;
        }

        boolean isFirstChat = chatSession.isFirstChat();
        chatSession.markFirstChatDone();
        if (isFirstChat) {
            generateTitleAsync(chatData, provider, model);
        }

        ToolCallNotifyAdvisor toolCallAdvise = new ToolCallNotifyAdvisor(
                startPayload -> channel.send(LlmMessageType.TOOL_CALL_START, startPayload),
                endPayload -> channel.send(LlmMessageType.TOOL_CALL_END, endPayload)
        );

        LlmChatAdapter adapter = adapterRegistry.getAdapter(provider.getAdapter());
        long startTime = System.currentTimeMillis();

        List<Object> dynamicTools = new ArrayList<>(registeredTools.values());
        ChatClient chatClient = chatClientService.getChatClient(
                provider, model, chatSession.getSessionId(), adapter,
                builder -> {
                    builder.defaultTools(dynamicTools.toArray());
                    builder.defaultToolContext(Map.of("user", chatSession.getUser()));
                })
                .mutate()
                .defaultAdvisors(toolCallAdvise)
                .build();

        this.chatDisposable = chatClient.prompt(Prompt.builder()
                        .messages(SystemMessage.builder()
                                .text("你的咸鱼云网盘 AI 助手，可以帮助用户整理网盘、查找文件。当前用户用户名为: " + chatSession.getUser().getUsername())
                                .build())
                        .build())
                .user(chatData.getContent())
                .stream()
                .chatResponse()
                .filter(msg -> !msg.getResults().isEmpty())
                .flatMap(msg -> Flux.fromStream(msg.getResults().stream()))
                .map(msg -> {
                    String text = msg.getOutput().getText();
                    String reasoningContent = adapter.extractReasoningContent(msg.getOutput());
                    TextPayload textPayload = new TextPayload();
                    textPayload.setContent(text);
                    textPayload.setReasoningContent(reasoningContent);
                    LlmResponse llmResp = new LlmResponse();
                    llmResp.setType(LlmMessageType.TEXT);
                    llmResp.setData(textPayload);
                    return llmResp;
                })
                .filter(response -> {
                    TextPayload data = (TextPayload) response.getData();
                    return StringUtils.hasText(data.getContent()) || StringUtils.hasText(data.getReasoningContent());
                })
                .doOnError(throwable -> {
                    channel.sendError("LLM 响应流处理过程中出错: " + throwable.getMessage());
                    log.error("ai 消息发送出错", throwable);
                })
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    DonePayload donePayload = new DonePayload();
                    donePayload.setReason("已完成");
                    donePayload.setModelId(model.getModelId());
                    donePayload.setTime(elapsed);
                    channel.send(LlmMessageType.DONE, donePayload);
                })
                .subscribe(response -> {
                    channel.send(response.getType(), response.getData());
                });
    }

    private void handleRegisterTool(UserRequest request) {
        RegisterToolPayload payload = MapperHolder.mapper.convertValue(
                request.getData(), RegisterToolPayload.class);
        registeredTools.put(payload.getName(),
                new ChannelMediatedToolCallback(
                        new RegisteredTool(payload.getName(), payload.getDescription(), payload.getParameters()),
                        channel, pendingToolCalls));
        log.debug("通过 MessageChannel 注册工具: {}", payload.getName());
        channel.send(LlmMessageType.REGISTER_TOOL_ACK, payload.getName());
    }

    private void handleToolAck(UserRequest request) {
        ToolCallAckPayload ack = MapperHolder.mapper.convertValue(
                request.getData(), ToolCallAckPayload.class);
        CompletableFuture<String> future = pendingToolCalls.remove(ack.getId());
        if (future != null) {
            future.complete(ack.getResult());
        } else {
            log.warn("收到未知工具调用 ID 的 TOOL_ACK: {}", ack.getId());
        }
    }

    private void handleStop() {
        DonePayload donePayload = new DonePayload();
        donePayload.setReason("已停止");
        channel.send(LlmMessageType.DONE, donePayload);
    }

    private void generateTitleAsync(ChatPayload chatData, LlmProvider provider, LlmModel model) {
        this.titleFuture = CompletableFuture.runAsync(() -> {
            try {
                LlmChatAdapter adapter = adapterRegistry.getAdapter(provider.getAdapter());
                ChatModel chatModel = adapter.createChatModel(provider, model);
                ChatClient bareClient = ChatClient.builder(chatModel).build();

                String title = bareClient.prompt()
                        .user("用20个字符以内总结以下消息：" + chatData.getContent())
                        .call()
                        .content();

                if (title == null || title.isBlank()) {
                    title = "新对话";
                }

                AiConversation conversation = new AiConversation();
                conversation.setConversationId(chatSession.getSessionId());
                conversation.setTitle(title);
                conversation.setUid(chatSession.getUser().getId());
                aiConversationService.save(conversation);

                TitleUpdatePayload titlePayload = new TitleUpdatePayload();
                titlePayload.setTitle(title);
                titlePayload.setConversationId(chatSession.getSessionId());
                channel.send(LlmMessageType.TITLE_UPDATE, titlePayload);
            } catch (Exception e) {
                log.error("生成对话标题失败", e);
            }
        });
    }
}
