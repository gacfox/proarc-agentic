package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工具消息响应信息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCall implements Serializable {
    /**
     * 工具调用ID
     */
    @JsonProperty("id")
    private String id;
    /**
     * 工具调用索引
     */
    @JsonProperty("index")
    private Integer index;
    /**
     * 工具调用类型，固定值function
     */
    @Builder.Default
    @JsonProperty("type")
    private String type = "function";
    /**
     * 工具调用函数信息
     */
    @JsonProperty("function")
    private ToolCallFunction function;
}
