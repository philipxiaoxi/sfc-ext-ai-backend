package com.sfc.ai.core.message;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;

/**
 * 带思维链内容的 LLM 响应消息
 */
public class ReasoningAssistantMessage extends AssistantMessage implements ReasoningContentSupport {
    private final String reasoningContent;


    public ReasoningAssistantMessage(@Nullable String content) {
        this(content, null);
    }

    public ReasoningAssistantMessage(@Nullable String content, @Nullable String reasoningContent) {
        super(content);
        this.reasoningContent = reasoningContent;
    }

    public ReasoningAssistantMessage(@Nullable String content, Map<String, Object> properties, List<ToolCall> toolCalls, List<Media> media) {
        this(content, null, properties, toolCalls, media);
    }

    public ReasoningAssistantMessage(@Nullable String content, @Nullable String reasoningContent, Map<String, Object> properties, List<ToolCall> toolCalls, List<Media> media) {
        super(content, properties, toolCalls, media);
        this.reasoningContent = reasoningContent;
    }

    @Override
    public String getReasoningContent() {
        return reasoningContent == null ? "" : reasoningContent;
    }
}
