package com.gacfox.proarc.agentic.prompt;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * Mustache提示词模板构建工具类
 */
public final class PromptTemplate {
    private static final MustacheFactory MUSTACHE_FACTORY = new DefaultMustacheFactory();

    /**
     * 构建提示词
     *
     * @param templateString Mustache模板字符串
     * @param params         模板参数
     * @return 构建后的提示词
     */
    public static String build(String templateString, Map<String, Object> params) {
        Mustache mustache = MUSTACHE_FACTORY.compile(new StringReader(templateString), "prompt");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, params);
        return writer.toString();
    }
}
