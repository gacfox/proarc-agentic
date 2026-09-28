package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式返回选项
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamOptions implements Serializable {
    /**
     * 是否包含使用情况
     */
    @Builder.Default
    @JsonProperty("include_usage")
    private Boolean includeUsage = true;
}
