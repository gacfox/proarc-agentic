package com.gacfox.proarc.agentic.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体单轮循环结果
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopResult {
    /**
     * 本轮产生的响应事件
     */
    @Builder.Default
    private List<AgentResponse> responses = new ArrayList<>();
    /**
     * 是否结束整次智能体执行
     */
    private boolean finished;
    /**
     * 是否由外部挂起执行（例如通过拦截器触发human-in-the-loop）
     */
    private boolean suspended;

    public static AgentLoopResult continueWith(List<AgentResponse> responses) {
        return AgentLoopResult.builder().responses(responses).finished(false).suspended(false).build();
    }

    public static AgentLoopResult finishWith(List<AgentResponse> responses) {
        return AgentLoopResult.builder().responses(responses).finished(true).suspended(false).build();
    }

    public static AgentLoopResult suspendWith(List<AgentResponse> responses) {
        return AgentLoopResult.builder().responses(responses).finished(false).suspended(true).build();
    }
}
