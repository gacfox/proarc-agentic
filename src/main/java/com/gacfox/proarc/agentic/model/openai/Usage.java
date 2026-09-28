package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Usage implements Serializable {
    /**
     * 输入消息tokens数
     */
    @JsonProperty("prompt_tokens")
    private Integer promptTokens;
    /**
     * 输出tokens数
     */
    @JsonProperty("completion_tokens")
    private Integer completionTokens;
    /**
     * 总tokens数
     */
    @JsonProperty("total_tokens")
    private Integer totalTokens;
}
