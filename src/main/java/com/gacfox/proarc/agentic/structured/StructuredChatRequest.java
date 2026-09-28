package com.gacfox.proarc.agentic.structured;

import com.gacfox.proarc.agentic.model.openai.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 结构化输出请求
 *
 * @param <T> 结构化输出类型
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StructuredChatRequest<T> implements Serializable {
    /**
     * 消息列表
     */
    private List<Message> messages;

    /**
     * 结构化输出类型
     */
    private Class<T> responseType;

    /**
     * 结构化输出工具名
     */
    @Builder.Default
    private String toolName = "structured_output";

    /**
     * 结构化输出额外指令
     */
    private String instruction;

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
}
