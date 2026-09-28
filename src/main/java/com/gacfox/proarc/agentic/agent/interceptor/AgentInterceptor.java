package com.gacfox.proarc.agentic.agent.interceptor;

import com.gacfox.proarc.agentic.agent.AgentContext;
import com.gacfox.proarc.agentic.agent.AgentLoopResult;

/**
 * 智能体循环环绕拦截器
 * <p>
 * 每一次ReAct循环都会执行一遍拦截器链。拦截器通过
 * {@link AgentInterceptorChain} 掌握本轮下游调用，在
 * {@code chain.next(context)} 前后编写本轮前置和后置逻辑。
 */
@FunctionalInterface
public interface AgentInterceptor {
    /**
     * 环绕拦截一次智能体循环
     *
     * @param context 智能体上下文
     * @param chain   拦截器链
     * @return 本轮循环结果
     */
    AgentLoopResult intercept(AgentContext context, AgentInterceptorChain chain);

    /**
     * 排序值，值更小的优先级越高
     *
     * @return 排序值
     */
    default int getOrder() {
        return 0;
    }
}
