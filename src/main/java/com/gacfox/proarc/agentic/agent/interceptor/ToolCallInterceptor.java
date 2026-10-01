package com.gacfox.proarc.agentic.agent.interceptor;

import com.gacfox.proarc.agentic.agent.ToolInvocation;

/**
 * 工具调用环绕拦截器
 * <p>
 * 每一次真正执行的工具调用（含内置final_answer）都会经过拦截器链。拦截器通过
 * {@link ToolCallChain} 掌握下游调用，在 {@code chain.proceed(invocation)}
 * 前后编写前置和后置逻辑，可用于审计、参数改写、结果替换、阻塞等待、
 * 拒绝执行等场景。典型用法示例：human-in-the-loop审批。
 * <p>
 * 拦截器返回的字符串即作为工具调用结果写入消息历史；抛出异常时按工具执行
 * 异常处理，异常信息会作为错误结果返回给模型；抛出
 * {@link com.gacfox.proarc.agentic.agent.AgentSuspendException} 时挂起本次
 * 智能体执行。
 */
@FunctionalInterface
public interface ToolCallInterceptor {
    /**
     * 环绕拦截一次工具调用
     *
     * @param invocation 工具调用信息，可改写arguments后再放行
     * @param chain      拦截器链
     * @return 工具调用结果字符串
     * @throws Exception 执行失败时抛出，按工具执行异常处理
     */
    String intercept(ToolInvocation invocation, ToolCallChain chain) throws Exception;

    /**
     * 排序值，值更小的优先级越高
     *
     * @return 排序值
     */
    default int getOrder() {
        return 0;
    }
}
