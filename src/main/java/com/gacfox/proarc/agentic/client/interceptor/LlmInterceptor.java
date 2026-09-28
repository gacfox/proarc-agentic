package com.gacfox.proarc.agentic.client.interceptor;

import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;

/**
 * 大语言模型客户端拦截器
 * <p>
 * 环绕式拦截：拦截器通过 {@link LlmInterceptorChain} 掌握下游调用，
 * 在 {@code chain.nextXxx()} 前后编写前置和后置逻辑，
 * 异常通过 try-catch（阻塞式）或 Reactor 操作符（流式）自行处理。
 */
public interface LlmInterceptor {

    /**
     * 阻塞式调用拦截
     *
     * @param request   请求
     * @param modelInfo 模型配置
     * @param chain     拦截器链
     * @return 响应
     */
    default ModelResponse interceptBlocking(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        return chain.nextBlocking(request);
    }

    /**
     * 流式调用拦截
     *
     * @param request   请求
     * @param modelInfo 模型配置
     * @param chain     拦截器链
     * @return 响应流
     */
    default Flux<ModelResponse> interceptStreaming(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        return chain.nextStreaming(request);
    }

    /**
     * 排序值，值更小的优先级越高
     *
     * @return 排序值
     */
    default int getOrder() {
        return 0;
    }
}
