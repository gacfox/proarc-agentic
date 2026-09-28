package com.gacfox.proarc.agentic.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 智能体工具
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticTool {
    /**
     * 工具名
     *
     * @return 工具名
     */
    String name();

    /**
     * 工具描述
     *
     * @return 工具描述
     */
    String description();
}
