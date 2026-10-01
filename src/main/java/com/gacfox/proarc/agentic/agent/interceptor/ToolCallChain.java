package com.gacfox.proarc.agentic.agent.interceptor;

import com.gacfox.proarc.agentic.agent.ToolInvocation;

/**
 * 工具调用拦截器链
 */
public interface ToolCallChain {
    /**
     * 调用链中下一个拦截器，若已是末尾则执行实际的工具调用
     *
     * @param invocation 工具调用信息
     * @return 工具调用结果字符串
     * @throws Exception 执行失败时抛出
     */
    String proceed(ToolInvocation invocation) throws Exception;
}
