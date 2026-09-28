package com.gacfox.proarc.agentic.agent;

import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.openai.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体执行上下文
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentContext implements Serializable {
    /**
     * 会话上下文ID
     */
    private String contextId;
    /**
     * 消息列表
     */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();
    /**
     * LLM客户端
     */
    private transient LlmClient llmClient;
    /**
     * 工具名称列表
     */
    private List<String> toolNames;
    /**
     * 温度
     */
    @Builder.Default
    private Double temperature = 0.7;
    /**
     * 是否开启思考
     */
    private Boolean enableThinking;
    /**
     * 核采样参数
     */
    private Double topP;
    /**
     * 前k个最可能token
     */
    private Integer topK;
    /**
     * 惩罚新出现的token
     */
    @Builder.Default
    private Double presencePenalty = 0.0;
    /**
     * 不惩罚重复出现的token
     */
    @Builder.Default
    private Double frequencyPenalty = 0.0;
    /**
     * 随机数种子
     */
    private Integer seed;
    /**
     * 控制生成文本长度
     */
    private Integer maxTokens;
    /**
     * 是否流式输出思考与最终回答内容（产生THINKING_DELTA/FINAL_ANSWER_DELTA事件），默认false一次性输出
     */
    @Builder.Default
    private boolean streaming = false;
    /**
     * 自定义变量，工具可读写状态
     */
    @Builder.Default
    private Map<String, Object> variables = new ConcurrentHashMap<>();

    /**
     * 创建当前智能体上下文快照，注意非完全深拷贝，仅用于防御性规避工具误改状态
     */
    public AgentContext snapshot() {
        return AgentContext.builder()
                .contextId(contextId)
                .messages(messages == null ? null : new ArrayList<>(messages))
                .llmClient(llmClient)
                .toolNames(toolNames == null ? null : new ArrayList<>(toolNames))
                .temperature(temperature)
                .enableThinking(enableThinking)
                .topP(topP)
                .topK(topK)
                .presencePenalty(presencePenalty)
                .frequencyPenalty(frequencyPenalty)
                .seed(seed)
                .maxTokens(maxTokens)
                .streaming(streaming)
                .variables(variables == null ? null : new ConcurrentHashMap<>(variables))
                .build();
    }
}
