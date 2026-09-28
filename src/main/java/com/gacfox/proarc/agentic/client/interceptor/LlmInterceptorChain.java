package com.gacfox.proarc.agentic.client.interceptor;

import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;

/**
 * 拦截器链，用于在拦截器内部调用下一个拦截器或最终的模型调用
 */
public interface LlmInterceptorChain {

    /**
     * 调用链中下一个拦截器的阻塞式处理，若已是末尾则执行实际的模型调用
     *
     * @param request 请求
     * @return 响应
     */
    ModelResponse nextBlocking(ModelRequest request);

    /**
     * 调用链中下一个拦截器的流式处理，若已是末尾则执行实际的模型调用
     *
     * @param request 请求
     * @return 响应流
     */
    Flux<ModelResponse> nextStreaming(ModelRequest request);
}
