package com.sfc.ai.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Configuration(proxyBeanMethods = false)
public class SfcAiJacksonConfig {

    /**
     * 注册 JsonMapperBuilderCustomizer，将 Long/long 序列化为 String。
     */
    @Bean
    public JsonMapperBuilderCustomizer longToStringJsonMapperBuilderCustomizer() {
        SimpleModule longToStringModule = new SimpleModule();
        // long -> String
        longToStringModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        // Long -> String
        longToStringModule.addSerializer(Long.class, ToStringSerializer.instance);
        return builder -> builder.addModule(longToStringModule);
    }
}
