package com.gacfox.proarc.agentic.model;

import com.gacfox.proarc.agentic.model.openai.Message;

/**
 * 工具调用结果消息
 */
public class ToolMessage extends Message {

    /**
     * 创建工具调用结果消息
     *
     * @param toolCallId 工具调用ID
     * @param content    工具调用结果
     */
    public ToolMessage(String toolCallId, String content) {
        setRole(ROLE_TOOL);
        setToolCallId(toolCallId);
        setContent(content);
    }
}
