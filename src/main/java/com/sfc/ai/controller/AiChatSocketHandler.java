package com.sfc.ai.controller;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.advisor.ToolCallNotifyAdvisor;
import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.constant.UserMessageType;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.message.UserRequest;
import com.sfc.ai.model.chat.payload.*;
import com.sfc.ai.model.chat.payload.TitleUpdatePayload;
import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.core.ChatClientService;
import com.sfc.ai.service.AiConversationService;
import com.sfc.ai.service.LlmModelService;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AI 聊天 WebSocket 处理器。
 * <p>
 * 消息格式为纯文本 JSON，使用 {@link MapperHolder} 进行序列化/反序列化。
 * 接收的消息反序列化为 {@link UserRequest}，发送的消息从 {@link LlmResponse} 序列化。
 * <p>
 * 协议约束：第一条消息必须为 {@link UserMessageType#START_SESSION}，
 * 且每个 WebSocket 连接仅允许发送一次该类型消息。
 */
@Slf4j
public class AiChatSocketHandler extends TextWebSocketHandler {

    private static final String SESSION_STARTED_KEY = "SESSION_STARTED";
    private static final String SESSION_ID_KEY = "SESSION_ID";
    private static final String FIRST_CHAT_KEY = "FIRST_CHAT";
    private static final String MSG_QUEUE_KEY = "MSG_QUEUE";
    private static final String MSG_PROC_KEY = "MSG_PROC";

    private final LlmModelService llmModelService;
    private final ChatClientService chatClientService;
    private final LlmProviderService llmProviderService;
    private final LlmChatAdapterRegistry adapterRegistry;
    private final AiConversationService aiConversationService;

    public AiChatSocketHandler(LlmModelService llmModelService, ChatClientService chatClientService,
                                LlmProviderService llmProviderService, LlmChatAdapterRegistry adapterRegistry,
                                AiConversationService aiConversationService) {
        this.llmModelService = llmModelService;
        this.chatClientService = chatClientService;
        this.llmProviderService = llmProviderService;
        this.adapterRegistry = adapterRegistry;
        this.aiConversationService = aiConversationService;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        UserPrincipal user = getUser(session);
        log.info("WebSocket 连接已建立，用户: {}", user != null ? user.getUsername() : "未知");
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到消息: {}", payload);

        UserRequest userRequest;
        try {
            userRequest = MapperHolder.parseJson(payload, UserRequest.class);
        } catch (Exception e) {
            log.warn("消息反序列化失败: {}", payload, e);
            sendError(session, "消息格式错误，无法解析");
            return;
        }

        if (userRequest.getType() == null) {
            sendError(session, "消息类型不能为空");
            return;
        }

        UserMessageType type = userRequest.getType();
        boolean sessionStarted = isSessionStarted(session);

        if (type == UserMessageType.START_SESSION) {
            if (sessionStarted) {
                sendError(session, "START_SESSION 仅允许发送一次");
                return;
            }
            markSessionStarted(session);

            StartSessionPayload startPayload = MapperHolder.mapper.convertValue(userRequest.getData(), StartSessionPayload.class);
            String sessionId = startPayload.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
            session.getAttributes().put(SESSION_ID_KEY, sessionId);
            // 检查是否为全新对话
            boolean isNew = !aiConversationService.existsByConversationId(sessionId);
            session.getAttributes().put(FIRST_CHAT_KEY, isNew);

            SessionAckPayload ackPayload = new SessionAckPayload(sessionId);
            LlmResponse response = new LlmResponse();
            response.setType(LlmMessageType.SESSION_ACK);
            response.setData(ackPayload);
            sendMessageSafe(session, new TextMessage(MapperHolder.toJson(response)));
            return;
        }

        if (!sessionStarted) {
            sendError(session, "请先发送 START_SESSION 消息开启会话");
            return;
        }

        try {
            switch (type) {
                case CHAT -> handleChat(session, userRequest);
                case TOOL_ACK -> handleToolAck(session, userRequest);
                case STOP -> handleStop(session);
                default -> sendError(session, "未知消息类型: " + type);
            }
        } catch (Throwable e) {
            sendError(session, "处理消息时发生错误: " + e.getMessage());
            log.error("处理 AI 消息时发生错误", e);
        }
    }

    /**
     * 处理聊天消息。
     */
    private void handleChat(WebSocketSession session, UserRequest request) throws Exception {
        ChatPayload chatData = MapperHolder.mapper.convertValue(request.getData(), ChatPayload.class);
        if (chatData.getModelId() == null || chatData.getContent() == null) {
            sendError(session, "CHAT 消息缺少 modelId 或 content");
            return;
        }

        LlmModel model = llmModelService.findById(chatData.getModelId());
        if (model == null) {
            sendError(session, "模型不存在");
            return;
        }

        UserPrincipal user = getUser(session);
        Long uid = model.getUid();
        if (uid != null && uid != 0 && (user == null || !uid.equals(user.getId()))) {
            sendError(session, "无权访问该模型");
            return;
        }

        LlmProvider provider = llmProviderService.findById(model.getLlmProviderId());
        if (provider == null) {
            sendError(session, "模型提供商不存在");
            return;
        }
        // 检测是否为全新对话的首次 CHAT，异步生成标题
        boolean isFirstChat = Boolean.TRUE.equals(session.getAttributes().get(FIRST_CHAT_KEY));
        session.getAttributes().put(FIRST_CHAT_KEY, false);
        if (isFirstChat) {
            generateTitleAsync(session, chatData, user, provider, model);
        }
        String sessionId = (String) session.getAttributes().get(SESSION_ID_KEY);
        ToolCallNotifyAdvisor toolCallAdvise = new ToolCallNotifyAdvisor(
                startPayload -> {
                    try {
                        sendJsonMessage(session, LlmMessageType.TOOL_CALL_START, startPayload);
                    } catch (Exception e) {
                        log.error("发送 TOOL_CALL_START 消息失败", e);
                    }
                },
                endPayload -> {
                    try {
                        sendJsonMessage(session, LlmMessageType.TOOL_CALL_END, endPayload);
                    } catch (Exception e) {
                        log.error("发送 TOOL_CALL_END 消息失败", e);
                    }
                }
        );
        LlmChatAdapter adapter = adapterRegistry.getAdapter(provider.getAdapter());
        assert user != null;
        long startTime = System.currentTimeMillis();
        ChatClient chatClient = chatClientService.getChatClient(provider, model, sessionId, adapter)
                .mutate()
                .defaultAdvisors(toolCallAdvise)
                .build();
        chatClient.prompt(Prompt.builder()
                        .messages(SystemMessage.builder()
                                .text("你的咸鱼云网盘 AI 助手，可以帮助用户整理网盘、查找文件。当前用户用户名为: " + user.getUsername())
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
                    LlmResponse response = new LlmResponse();
                    response.setType(LlmMessageType.TEXT);
                    response.setData(textPayload);
                    return response;
                })
                .filter(response -> {
                    TextPayload data = (TextPayload) response.getData();
                    return StringUtils.hasText(data.getContent()) || StringUtils.hasText(data.getReasoningContent());
                })
                .doOnError(throwable -> {
                    try {
                        sendError(session, "LLM 响应流处理过程中出错: " + throwable.getMessage());
                    } catch (Exception e) {
                        log.error("ai 消息发送出错", e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        long elapsed = System.currentTimeMillis() - startTime;
                        DonePayload donePayload = new DonePayload();
                        donePayload.setReason("已完成");
                        donePayload.setModelId(model.getModelId());
                        donePayload.setTime(elapsed);
                        sendJsonMessage(session, LlmMessageType.DONE, donePayload);
                    } catch (Exception e) {
                        log.error("发送 DONE 消息失败", e);
                    }
                })
                .subscribe(response -> {
                    sendMessageSafe(session, new TextMessage(MapperHolder.toJsonNoEx(response)));
                });


    }

    /**
     * 处理工具调用确认。
     */
    private void handleToolAck(WebSocketSession session, UserRequest request) throws Exception {
        // TODO: 处理工具调用确认
        TextPayload textPayload = new TextPayload();
        textPayload.setContent("工具确认已收到");
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.TEXT);
        response.setData(textPayload);
        sendMessageSafe(session, new TextMessage(MapperHolder.toJson(response)));
    }

    /**
     * 处理停止请求。
     */
    private void handleStop(WebSocketSession session) throws Exception {
        // TODO: 停止 LLM 响应
        DonePayload donePayload = new DonePayload();
        donePayload.setReason("已停止");
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.DONE);
        response.setData(donePayload);
        sendMessageSafe(session, new TextMessage(MapperHolder.toJson(response)));
    }

    /**
     * 异步生成对话标题并保存。
     */
    private void generateTitleAsync(WebSocketSession session, ChatPayload chatData, UserPrincipal user,
                                     LlmProvider provider, LlmModel model) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID_KEY);
        if (sessionId == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                String adapterId = provider.getAdapter();
                LlmChatAdapter adapter = adapterRegistry.getAdapter(adapterId);
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
                conversation.setConversationId(sessionId);
                conversation.setTitle(title);
                conversation.setUid(user.getId());
                aiConversationService.save(conversation);

                TitleUpdatePayload titlePayload = new TitleUpdatePayload();
                titlePayload.setTitle(title);
                titlePayload.setConversationId(sessionId);
                sendJsonMessage(session, LlmMessageType.TITLE_UPDATE, titlePayload);
            } catch (Exception e) {
                log.error("生成对话标题失败", e);
            }
        });
    }

    /**
     * 发送错误消息。
     */
    private void sendJsonMessage(WebSocketSession session, LlmMessageType type, Object data) {
        LlmResponse response = new LlmResponse();
        response.setType(type);
        response.setData(data);
        sendMessageSafe(session, new TextMessage(MapperHolder.toJsonNoEx(response)));
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            ErrorPayload errorPayload = new ErrorPayload();
            errorPayload.setMessage(errorMessage);
            LlmResponse response = new LlmResponse();
            response.setType(LlmMessageType.ERROR);
            response.setData(errorPayload);
            sendMessageSafe(session, new TextMessage(MapperHolder.toJson(response)));
        } catch (Exception e) {
            log.error("发送错误消息失败", e);
        }
    }

    /**
     * 安全地发送 WebSocket 消息。所有发送调用都应通过此方法，而非直接调用
     * {@code WebSocketSession.sendMessage(TextMessage)}。
     * <p>
     * 方法内部对每个 WebSocket 会话维护一个消息队列，新消息先入队，再由单个线程
     * 依次排空并发送，从而避免多线程并发调用 {@code session.sendMessage} 导致的
     * 数据交错或连接异常。
     */
    @SuppressWarnings("unchecked")
    private void sendMessageSafe(WebSocketSession session, TextMessage message) {
        ConcurrentLinkedQueue<TextMessage> queue = (ConcurrentLinkedQueue<TextMessage>)
                session.getAttributes().computeIfAbsent(MSG_QUEUE_KEY, k -> new ConcurrentLinkedQueue<>());
        queue.add(message);
        tryDrain(session, queue);
    }

    /**
     * 尝试排空指定会话的消息队列。
     * <p>
     * 使用 {@link #MSG_PROC_KEY} 作为处理标记确保同一时间只有一个线程在发送消息。
     * 发送完所有排队消息后清除标记，再检查一次队列是否有新消息（处理并发入队竞争），
     * 若有则递归继续排空。
     */
    private void tryDrain(WebSocketSession session, ConcurrentLinkedQueue<TextMessage> queue) {
        synchronized (session) {
            if (Boolean.TRUE.equals(session.getAttributes().get(MSG_PROC_KEY))) {
                return;
            }
            session.getAttributes().put(MSG_PROC_KEY, Boolean.TRUE);
        }

        try {
            while (true) {
                TextMessage msg = queue.poll();
                if (msg == null) break;
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    log.error("WebSocket 消息发送失败", e);
                    break;
                }
            }
        } finally {
            synchronized (session) {
                session.getAttributes().put(MSG_PROC_KEY, Boolean.FALSE);
            }
            if (!queue.isEmpty()) {
                tryDrain(session, queue);
            }
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error("WebSocket 传输异常", exception);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭，状态: {}", status);
    }

    /**
     * 判断当前会话是否已通过 START_SESSION 初始化。
     */
    private boolean isSessionStarted(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get(SESSION_STARTED_KEY));
    }

    /**
     * 标记会话已初始化。
     */
    private void markSessionStarted(WebSocketSession session) {
        session.getAttributes().put(SESSION_STARTED_KEY, Boolean.TRUE);
    }

    /**
     * 从 WebSocket 会话中获取当前登录用户。
     *
     * @param session WebSocket 会话
     * @return 用户主体，如果未认证则返回 null
     */
    private UserPrincipal getUser(WebSocketSession session) {
        if (session.getPrincipal() instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof UserPrincipal user) {
            return user;
        }
        return null;
    }
}
