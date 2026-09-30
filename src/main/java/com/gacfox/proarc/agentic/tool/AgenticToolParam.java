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

    /**
     * 递归DTO字段允许嵌套展开的最大层数，仅当字段类型为递归DTO（如菜单树）时生效。
     * 达到最大层数后该递归字段在schema中被省略（并输出WARN日志），使模型无法生成更深层级。
     * 默认1表示不允许递归，未声明maxDepth的循环引用会在注册期抛出IllegalArgumentException
     *
     * @return 最大嵌套展开层数
     */
    int maxDepth() default 1;
}
