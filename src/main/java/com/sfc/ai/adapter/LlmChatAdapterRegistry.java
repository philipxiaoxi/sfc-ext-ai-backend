package com.sfc.ai.adapter;

import com.xiaotao.saltedfishcloud.exception.JsonException;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 聊天适配器注册表。
 * <p>
 * 通过 Spring 的 {@link Autowired} 自动收集容器中所有 {@link LlmChatAdapter} Bean，
 * 同时提供 {@link #registerAdapter(LlmChatAdapter)} 方法支持手动注册。
 * <p>
 * 其他插件只需将自己的 LlmChatAdapter 实现注册为 Spring Bean，即可被自动发现。
 */
public class LlmChatAdapterRegistry {

    private final Map<String, LlmChatAdapter> adapterMap = new LinkedHashMap<>();

    /**
     * 自动收集 Spring 容器中所有 LlmChatAdapter 类型的 Bean。
     *
     * @param adapters 所有已发现的适配器列表
     */
    @Autowired(required = false)
    public void setAdapters(List<LlmChatAdapter> adapters) {
        if (adapters != null) {
            for (LlmChatAdapter adapter : adapters) {
                registerAdapter(adapter);
            }
        }
    }

    /**
     * 手动注册一个适配器。
     * <p>
     * 如果已存在相同 ID 的适配器，则忽略该次注册。
     *
     * @param adapter 适配器实例
     */
    public void registerAdapter(LlmChatAdapter adapter) {
        if (!adapterMap.containsKey(adapter.getId())) {
            adapterMap.put(adapter.getId(), adapter);
        }
    }

    /**
     * 根据适配器 ID 获取对应的适配器实例。
     *
     * @param id 适配器 ID
     * @return 适配器实例
     * @throws JsonException 如果找不到对应适配器
     */
    public LlmChatAdapter getAdapter(String id) {
        LlmChatAdapter adapter = adapterMap.get(id);
        if (adapter == null) {
            throw new JsonException("不支持的适配器: " + id);
        }
        return adapter;
    }

    /**
     * 获取系统当前支持的所有适配器元信息列表。
     *
     * @return 适配器信息列表
     */
    public List<AdapterInfo> listAdapters() {
        return adapterMap.values().stream()
                .map(a -> new AdapterInfo(a.getId(), a.getName(), a.getIcon()))
                .toList();
    }
}
