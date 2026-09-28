package com.gacfox.proarc.agentic.agent.interceptor;

import com.gacfox.proarc.agentic.agent.AgentContext;
import com.gacfox.proarc.agentic.agent.AgentLoopResult;

/**
 * 智能体单轮循环拦截器链
 */
public interface AgentInterceptorChain {
    /**
     * 调用链中下一个拦截器，若已是末尾则执行实际的单轮智能体循环
     *
     * @param context 智能体上下文
     * @return 本轮循环结果
     */
    AgentLoopResult next(AgentContext context);
}
