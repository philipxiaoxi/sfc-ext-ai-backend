package com.sfc.ai.controller;

import com.sfc.cloud.remote.client.SfcCoreConfigFeignClient;
import com.sfc.common.model.ConfigNode;
import com.sfc.common.model.PluginConfigNodeInfo;
import com.sfc.common.model.PluginInfo;
import com.sfc.common.model.json.SfcJsonResult;
import com.sfc.common.utils.PropertyUtils;
import com.sfc.common.utils.SfcMapperHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {
    private final SfcCoreConfigFeignClient sfcCoreConfigFeignClient;

    @RequestMapping("pluginInfo")
    @ResponseBody
    public PluginConfigNodeInfo mate() throws IOException {
        try (InputStream propertiesInputStream = new ClassPathResource("config-properties.json").getInputStream();
             InputStream pluginInfoInputStream = new ClassPathResource("plugin-info.json").getInputStream();
        ) {
            // 从配置文件构建插件信息实体类
            List<ConfigNode> groups = SfcMapperHolder.parseJsonToList(StreamUtils.copyToString(propertiesInputStream, StandardCharsets.UTF_8), ConfigNode.class);
            PluginInfo pluginInfo = SfcMapperHolder.parseJson(StreamUtils.copyToString(pluginInfoInputStream, StandardCharsets.UTF_8), PluginInfo.class);
            PluginConfigNodeInfo info = new PluginConfigNodeInfo();

            // 配置节点解引用
            PropertyUtils.dereferenceNodes(groups);

            // 展开嵌套节点，获取所有配置名以及配置值
            List<ConfigNode> flattenNodes = PropertyUtils.flattenNodes(groups);
            List<String> keyNames = flattenNodes.stream().map(ConfigNode::getName)
                    .filter(Objects::nonNull)
                    .filter(StringUtils::hasText)
                    .distinct().toList();
            if (!keyNames.isEmpty()) {
                Map<String, String> kv = sfcCoreConfigFeignClient.batchGetConfig(keyNames).requireSuccess().getData();
                for (ConfigNode node : flattenNodes) {
                    if (node.getName() != null && kv.containsKey(node.getName())) {
                        node.setValue(kv.get(node.getName()));
                    }
                }
            }
            info.setGroups(groups);
            info.setIcon(pluginInfo.getIcon());
            info.setName(pluginInfo.getName());
            info.setAlias(pluginInfo.getAlias());
            return info;
        }
    }
}
