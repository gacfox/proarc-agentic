package com.gacfox.proarc.agentic.client.header;

import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;

import java.util.Map;

/**
 * LLM请求动态Header提供者
 * <p>
 * 每次发起LLM请求时调用，返回值中的键值对将覆盖同名默认Header与{@link ModelInfo#getHeaders()}中的静态Header，
 * 可用于实现动态鉴权（token轮换、AK/SK签名）、链路追踪（traceId）、租户路由等逻辑。
 */
@FunctionalInterface
public interface LlmHeaderProvider {

    /**
     * 解析本次请求需要附加的Header
     *
     * @param modelInfo 模型配置
     * @param request   模型请求
     * @return 需要附加的Header键值对，返回null或空Map表示不附加任何Header
     */
    Map<String, String> resolve(ModelInfo modelInfo, ModelRequest request);
}
