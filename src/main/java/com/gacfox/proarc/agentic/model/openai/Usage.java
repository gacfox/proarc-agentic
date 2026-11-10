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
    /**
     * 输入tokens明细
     */
    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;

    /**
     * 获取输入中命中缓存的tokens数
     *
     * @return 缓存tokens数，provider未上报时返回null
     */
    public Integer getCachedTokens() {
        return promptTokensDetails != null ? promptTokensDetails.getCachedTokens() : null;
    }
}
