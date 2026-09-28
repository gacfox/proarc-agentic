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
 * 参数属性
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Property implements Serializable {
    /**
     * 参数类型
     */
    @JsonProperty("type")
    private String type;
    /**
     * 参数描述
     */
    @JsonProperty("description")
    private String description;
    /**
     * 对象属性
     */
    @JsonProperty("properties")
    private Map<String, Property> properties;
    /**
     * 对象必填属性
     */
    @JsonProperty("required")
    private List<String> required;
    /**
     * 数组元素定义
     */
    @JsonProperty("items")
    private Property items;
}
