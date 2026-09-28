package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大语言模型回复
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Choice implements Serializable {
    /**
     * 每条回复的索引
     */
    @JsonProperty("index")
    private Integer index;
    /**
     * 停止原因: null 生成中，stop 正常完成，tool_calls 工具调用，length 超出长度结束
     */
    @JsonProperty("finish_reason")
    private String finishReason;
    /**
     * 非流式响应消息
     */
    @JsonProperty("message")
    private Message message;
    /**
     * 流式响应消息
     */
    @JsonProperty("delta")
    private Delta delta;
}
