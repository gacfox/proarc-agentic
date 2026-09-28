package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工具调用响应调用函数信息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCallFunction implements Serializable {
    /**
     * 工具名
     */
    @JsonProperty("name")
    private String name;
    /**
     * 工具参数JSON字符串
     */
    @JsonProperty("arguments")
    private String arguments;
}
