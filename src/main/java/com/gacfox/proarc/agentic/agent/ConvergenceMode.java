package com.gacfox.proarc.agentic.agent;

/**
 * ReAct智能体循环收敛方式
 */
public enum ConvergenceMode {
    /**
     * 模型未发起工具调用时，将正文作为最终答案并结束循环
     */
    NO_TOOL_CALL,
    /**
     * 模型必须调用内置final_answer工具提交最终答案
     */
    FINAL_ANSWER
}
