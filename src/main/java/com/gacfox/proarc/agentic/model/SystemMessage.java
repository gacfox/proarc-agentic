package com.gacfox.proarc.agentic.model;

import com.gacfox.proarc.agentic.model.openai.Message;

/**
 * 系统消息
 */
public class SystemMessage extends Message {

    /**
     * 创建系统消息
     *
     * @param content 文本内容
     */
    public SystemMessage(String content) {
        setRole(ROLE_SYSTEM);
        setContent(content);
    }
}
