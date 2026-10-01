package com.gacfox.proarc.agentic.agent;

/**
 * 智能体挂起信号
 * <p>
 * 从工具调用拦截器或工具内部抛出时，执行器中断当前批次剩余的工具调用
 * （未执行的tool_calls保持待执行状态写入消息历史），发出SUSPENDED事件
 * 并正常结束事件流。调用方持久化上下文后，可将审批决定等状态写入
 * {@link AgentContext#getVariables()} 并再次调用execute恢复执行：
 * 执行器会先把历史中未配对结果的tool_calls执行完（重新经过拦截器链），
 * 再继续正常循环。
 */
public class AgentSuspendException extends RuntimeException {
    public AgentSuspendException(String message) {
        super(message);
    }

    public AgentSuspendException(String message, Throwable cause) {
        super(message, cause);
    }
}
