package com.sfc.ai.core;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.service.LlmModelService;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.RequiredArgsConstructor;

/**
 * LLM 解析器。
 * <p>
 * 把 {@code modelId} 解析为 {@code model → provider → adapter} 三元组，
 * 并在内部完成模型存在性与私有模型访问权校验。
 * 校验失败时抛出 {@link JsonException}，由调用方决定如何向 channel 解释。
 */
@RequiredArgsConstructor
public class LlmResolver {

    private final LlmModelService llmModelService;
    private final LlmProviderService llmProviderService;
    private final LlmChatAdapterRegistry adapterRegistry;

    /**
     * 解析模型并校验访问权限，返回三元组上下文。
     *
     * @param modelId 模型 ID
     * @param caller  当前用户，用于私有模型权限校验；可为 null 表示匿名
     * @return LLM 调用上下文
     * @throws JsonException 模型不存在 / 无权访问 / 提供商不存在时抛出
     */
    public LlmContext resolve(Long modelId, UserPrincipal caller) {
        LlmModel model = llmModelService.findById(modelId);
        if (model == null) {
            throw new JsonException("模型不存在");
        }

        Long modelUid = model.getUid();
        if (modelUid != null && modelUid != 0
                && (caller == null || !modelUid.equals(caller.getId()))) {
            throw new JsonException("无权访问该模型");
        }

        LlmProvider provider = llmProviderService.findById(model.getLlmProviderId());
        if (provider == null) {
            throw new JsonException("模型提供商不存在");
        }

        LlmChatAdapter adapter = adapterRegistry.getAdapter(provider.getAdapter());
        return new LlmContext(model, provider, adapter);
    }

    /**
     * LLM 调用上下文，封装一轮 CHAT 所需的 model / provider / adapter 三元组。
     */
    public record LlmContext(LlmModel model, LlmProvider provider, LlmChatAdapter adapter) {
    }
}