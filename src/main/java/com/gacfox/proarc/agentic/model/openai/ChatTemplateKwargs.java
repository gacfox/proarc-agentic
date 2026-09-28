package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 对话模板参数
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatTemplateKwargs implements Serializable {
    /**
     * 是否开启思考
     */
    @JsonProperty("enable_thinking")
    private Boolean enableThinking;
}
