package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 函数工具信息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Function implements Serializable {
    /**
     * 工具名
     */
    @JsonProperty("name")
    private String name;
    /**
     * 工具描述
     */
    @JsonProperty("description")
    private String description;
    /**
     * 参数描述
     */
    @JsonProperty("parameters")
    private Parameters parameters;
}
