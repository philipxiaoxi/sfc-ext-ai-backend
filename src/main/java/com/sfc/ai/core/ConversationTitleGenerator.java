package com.sfc.ai.core;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.LlmResolver.LlmContext;
import com.sfc.ai.core.SessionContext;
import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.model.chat.payload.TitleUpdatePayload;
import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.service.AiConversationService;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 对话标题生成器。
 * <p>
 * 负责根据用户首条聊天消息内容，通过 LLM 自动生成对话标题，
 * 并持久化保存到 {@link AiConversation} 中，同时向客户端推送标题更新消息。
 */
@Slf4j
@RequiredArgsConstructor
public class ConversationTitleGenerator {

    private final LlmChatAdapterRegistry adapterRegistry;
    private final AiConversationService aiConversationService;

    /**
     * 若启用且为首聊，异步发起标题生成并回写 Future 到 {@link SessionContext}；否则无操作。
     * <p>
     * 内部使用 {@link CompletableFuture#runAsync(Runnable)} 触发，
     * {@code generate} 抛出的异常会被吞掉仅记日志，不影响主流程；
     * 生成的 Future 通过 {@link SessionContext#setTitleFuture(CompletableFuture)} 回写，
     * 以便中断时被 {@link SessionContext#dispose()} 取消。
     *
     * @param channel             消息通道，用于推送标题更新
     * @param sessionContext      当前会话上下文，用于读取 conversationId / user 与回写 Future
     * @param userContent         用户首条消息内容
     * @param ctx                 LLM 调用上下文（用于构造 bare client）
     * @param autoGenerateTitle   是否启用首聊自动生成标题
     *                            （由上游 {@code AgentExecutorConfig} 透传；
     *                             子 Agent 体系可置 false 以跳过标题生成）
     */
    public void fireIfNew(MessageChannel channel,
                          SessionContext sessionContext,
                          String userContent,
                          LlmContext ctx,
                          boolean autoGenerateTitle) {
        if (!autoGenerateTitle) {
            return;
        }
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                generate(channel, sessionContext.getConversationId(),
                        sessionContext.getUser().getId(), userContent,
                        ctx.provider(), ctx.model());
            } catch (Exception e) {
                log.error("生成对话标题失败", e);
            }
        });
        sessionContext.setTitleFuture(future);
    }

    /**
     * 根据用户首条消息生成对话标题。
     *
     * @param channel        消息通道，用于推送标题更新
     * @param conversationId 会话 ID
     * @param uid            用户 ID
     * @param userContent    用户首条消息内容
     * @param provider       LLM 提供商
     * @param model          LLM 模型
     */
    public void generate(MessageChannel channel,
                         String conversationId,
                         Long uid,
                         String userContent,
                         LlmProvider provider,
                         LlmModel model) {
        try {
            LlmChatAdapter adapter = adapterRegistry.getAdapter(provider.getAdapter());
            ChatModel chatModel = adapter.createChatModel(provider, model);
            ChatClient bareClient = ChatClient.builder(chatModel).build();

            String title = bareClient.prompt()
                    .user("用20个字符以内总结以下消息：" + userContent)
                    .call()
                    .content();

            if (title == null || title.isBlank()) {
                title = "新对话";
            }

            AiConversation conversation = new AiConversation();
            conversation.setConversationId(conversationId);
            conversation.setTitle(title);
            conversation.setUid(uid);
            aiConversationService.save(conversation);

            TitleUpdatePayload titlePayload = new TitleUpdatePayload();
            titlePayload.setTitle(title);
            titlePayload.setConversationId(conversationId);
            channel.send(LlmMessageType.TITLE_UPDATE, titlePayload);
        } catch (Exception e) {
            log.error("生成对话标题失败", e);
        }
    }
}
