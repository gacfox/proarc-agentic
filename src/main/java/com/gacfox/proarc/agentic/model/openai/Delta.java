package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 大语言模型流式回复
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Delta implements Serializable {
    /**
     * 消息角色
     */
    @JsonProperty("role")
    private String role;
    /**
     * reasoning内容
     */
    @JsonProperty("reasoning")
    @JsonAlias({"thinking", "reasoning_content"})
    private String reasoning;
    /**
     * 消息内容
     */
    @JsonProperty("content")
    private String content;
    /**
     * 工具调用列表
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;
}
