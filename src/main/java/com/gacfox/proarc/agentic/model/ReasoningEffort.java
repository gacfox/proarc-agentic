package com.gacfox.proarc.agentic.model;

/**
 * 推理强度内置参考值。该参数不限制为枚举，也可直接传入Provider支持的其他字符串
 */
public final class ReasoningEffort {
    public static final String NONE = "none";
    public static final String MINIMAL = "minimal";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String XHIGH = "xhigh";
    public static final String MAX = "max";

    private ReasoningEffort() {
    }
}
