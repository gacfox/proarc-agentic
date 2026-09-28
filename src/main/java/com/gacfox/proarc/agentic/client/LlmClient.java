package com.gacfox.proarc.agentic.client;

import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;

/**
 * 大语言模型客户端
 */
public interface LlmClient {

    /**
     * 获取模型配置信息
     *
     * @return 模型配置信息
     */
    ModelInfo getModelInfo();

    /**
     * 阻塞式调用
     *
     * @param chatRequest 对话请求
     * @return 响应信息
     */
    ModelResponse blockingChat(ChatRequest chatRequest);

    /**
     * 流式调用
     *
     * @param chatRequest 对话请求
     * @return 响应信息流
     */
    Flux<ModelResponse> streamingChat(ChatRequest chatRequest);
}
