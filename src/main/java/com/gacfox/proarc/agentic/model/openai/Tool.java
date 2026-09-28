package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工具信息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tool implements Serializable {
    /**
     * 工具类型，固定值function
     */
    @Builder.Default
    @JsonProperty("type")
    private String type = "function";
    /**
     * 函数工具信息
     */
    @JsonProperty("function")
    private Function function;
}
