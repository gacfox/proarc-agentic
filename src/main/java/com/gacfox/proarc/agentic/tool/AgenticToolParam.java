package com.gacfox.proarc.agentic.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 智能体工具参数
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticToolParam {
    /**
     * 参数名称
     *
     * @return 参数名称
     */
    String name();

    /**
     * 参数描述
     *
     * @return 参数描述
     */
    String description();

    /**
     * 是否必填
     *
     * @return 是否必填
     */
    boolean required() default true;
}
