package com.gacfox.proarc.agentic.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次工具调用的完整信息，在工具调用拦截器链中传递
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolInvocation {
    /**
     * 工具调用ID
     */
    private String toolCallId;
    /**
     * 工具名
     */
    private String toolName;
    /**
     * 工具参数JSON字符串，拦截器可改写后放行
     */
    private String arguments;
    /**
     * 智能体上下文（活引用），拦截器可读写variables
     */
    private AgentContext agentContext;
}
