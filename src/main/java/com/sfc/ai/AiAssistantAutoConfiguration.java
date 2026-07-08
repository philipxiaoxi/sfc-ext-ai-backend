package com.sfc.ai;

import com.sfc.ai.controller.AiAssistantController;
import org.springframework.context.annotation.Import;

@Import({
        AiAssistantController.class
})
public class AiAssistantAutoConfiguration {
}
