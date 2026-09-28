package com.gacfox.proarc.agentic.tool;

import com.gacfox.proarc.agentic.agent.AgentContext;

/**
 * 智能体工具执行入口
 */
@FunctionalInterface
public interface ToolInvoker {
    /**
     * 执行工具调用
     *
     * @param arguments 工具入参的JSON字符串，无参工具为null或空串
     * @param context   当前智能体执行上下文快照
     * @return 工具执行结果
     * @throws Exception 工具执行异常
     */
    String invoke(String arguments, AgentContext context) throws Exception;
}
