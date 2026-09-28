package com.gacfox.proarc.agentic.structured;

import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import com.gacfox.proarc.agentic.model.openai.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

/**
 * 结构化输出响应
 *
 * @param <T> 结构化输出类型
 */
@Getter
@AllArgsConstructor
public class StructuredResponse<T> implements Serializable {
    /**
     * 结构化结果
     */
    private final T result;
    /**
     * 工具参数JSON
     */
    private final String arguments;
    /**
     * 原始模型响应
     */
    private final ModelResponse rawResponse;
    /**
     * 原始工具调用
     */
    private final ToolCall toolCall;

    /**
     * 提取结构化结果
     *
     * @return 结构化结果
     */
    public T extract() {
        return result;
    }
}
