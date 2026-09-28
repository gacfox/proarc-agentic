package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 参数列表描述
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Parameters implements Serializable {
    /**
     * 结构类型，固定值object
     */
    @Builder.Default
    @JsonProperty("type")
    private String type = "object";
    /**
     * 参数列表，参数名为Key，参数值类型和描述结构为Value
     */
    @JsonProperty("properties")
    private Map<String, Property> properties;
    /**
     * 必选参数列表
     */
    @JsonProperty("required")
    private List<String> required;
}
