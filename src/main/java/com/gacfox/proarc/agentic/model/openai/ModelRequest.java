package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 标准（OpenAI规范）大模型请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelRequest implements Serializable {
    /**
     * 模型名称
     */
    @JsonProperty("model")
    private String model;
    /**
     * 消息
     */
    @JsonProperty("messages")
    private List<Message> messages;
    /**
     * 是否流式返回
     */
    @Builder.Default
    @JsonProperty("stream")
    private Boolean stream = true;
    /**
     * 流式返回选项
     */
    @Builder.Default
    @JsonProperty("stream_options")
    private StreamOptions streamOptions = StreamOptions.builder().build();
    /**
     * 控制生成文本长度，包含输入输出总tokens
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    /**
     * 惩罚新出现的token，抑制模型生成重复内容，值越大越倾向于重复已有内容，值越小越容易引入新话题
     */
    @Builder.Default
    @JsonProperty("presence_penalty")
    private Double presencePenalty = 0.0;
    /**
     * 不惩罚重复出现的token，抑制模型重复生成相同词汇或短语，与presence_penalty共同作用，前者惩罚首次出现，后者惩罚多次出现
     */
    @Builder.Default
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty = 0.0;
    /**
     * 随机数种子
     */
    @JsonProperty("seed")
    private Integer seed;
    /**
     * 温度
     */
    @Builder.Default
    @JsonProperty("temperature")
    private Double temperature = 0.7;
    /**
     * 核采样参数
     */
    @JsonProperty("top_p")
    private Double topP;
    /**
     * 前k个最可能token（仅部分Provider支持）
     */
    @JsonProperty("top_k")
    private Integer topK;
    /**
     * 对话模板参数
     */
    @JsonProperty("chat_template_kwargs")
    private ChatTemplateKwargs chatTemplateKwargs;
    /**
     * 请求工具调用描述信息列表
     */
    @JsonProperty("tools")
    private List<Tool> tools;

    /**
     * 工具选择策略
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;
}
