package com.gacfox.proarc.agentic.model;

import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.ToolCall;

import java.util.List;

/**
 * 助手消息
 */
public class AssistantMessage extends Message {

    /**
     * 创建助手消息
     *
     * @param content 文本内容
     */
    public AssistantMessage(String content) {
        setRole(ROLE_ASSISTANT);
        setContent(content);
    }

    /**
     * 创建带工具调用的助手消息
     *
     * @param content   文本内容
     * @param toolCalls 工具调用列表
     */
    public AssistantMessage(String content, List<ToolCall> toolCalls) {
        setRole(ROLE_ASSISTANT);
        setContent(content);
        setToolCalls(toolCalls);
    }
}
