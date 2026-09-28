package com.gacfox.proarc.agentic.config;

import com.gacfox.proarc.agentic.tool.AgenticTool;
import com.gacfox.proarc.agentic.tool.ToolRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;

@Configuration
public class ToolRegistryAutoConfiguration {

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public BeanPostProcessor toolRegistryBeanPostProcessor(ToolRegistry toolRegistry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(AgenticTool.class)) {
                        toolRegistry.register(bean);
                        break;
                    }
                }
                return bean;
            }
        };
    }
}
