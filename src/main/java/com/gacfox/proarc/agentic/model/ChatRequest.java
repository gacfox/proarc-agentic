package com.gacfox.proarc.agentic.model;

import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.Tool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 用户面向的大模型对话请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest implements Serializable {
    /**
     * 消息列表
     */
    private List<Message> messages;

    /**
     * 温度
     */
    @Builder.Default
    private Double temperature = 0.7;

    /**
     * 是否开启思考（仅特定模型支持，且思考输出方式存在reasoning字段和think xml标签两种形式，未配置reasoning能力的模型和本身不支持reasoning的模型设置该字段不生效）
     */
    private Boolean enableThinking;

    /**
     * 核采样参数
     */
    private Double topP;

    /**
     * 前k个最可能token（仅部分Provider支持）
     */
    private Integer topK;

    /**
     * 惩罚新出现的token，抑制模型生成重复内容，值越大越倾向于重复已有内容，值越小越容易引入新话题
     */
    @Builder.Default
    private Double presencePenalty = 0.0;

    /**
     * 不惩罚重复出现的token，抑制模型重复生成相同词汇或短语，与presence_penalty共同作用，前者惩罚首次出现，后者惩罚多次出现
     */
    @Builder.Default
    private Double frequencyPenalty = 0.0;

    /**
     * 随机数种子
     */
    private Integer seed;

    /**
     * 控制生成文本长度，包含输入输出总tokens
     */
    private Integer maxTokens;

    /**
     * 请求工具调用描述信息列表
     */
    private List<Tool> tools;

    /**
     * 工具选择策略
     */
    private Object toolChoice;
}
