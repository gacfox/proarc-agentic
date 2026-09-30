package com.gacfox.proarc.agentic.config;

import com.gacfox.proarc.agentic.tool.AgenticTool;
import com.gacfox.proarc.agentic.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryAutoConfigurationTest {

    interface TimeService {
        String currentTime();
    }

    static class TimeTools implements TimeService {
        @AgenticTool(name = "current_time", description = "获取当前时间")
        @Override
        public String currentTime() {
            return "12:00";
        }
    }

    static class WeatherTools {
        @AgenticTool(name = "query_weather", description = "查询天气")
        public String queryWeather() {
            return "晴";
        }
    }

    private BeanPostProcessor beanPostProcessor(ToolRegistry registry) {
        return new ToolRegistryAutoConfiguration().toolRegistryBeanPostProcessor(registry);
    }

    @Test
    void registersPlainBean() {
        ToolRegistry registry = new ToolRegistry();
        Object bean = new WeatherTools();

        beanPostProcessor(registry).postProcessAfterInitialization(bean, "weatherTools");

        assertThat(registry.getAgenticTool("query_weather")).isNotNull();
    }

    @Test
    void registersCglibProxiedBean() {
        ToolRegistry registry = new ToolRegistry();
        ProxyFactory proxyFactory = new ProxyFactory(new WeatherTools());
        proxyFactory.setProxyTargetClass(true);
        Object proxy = proxyFactory.getProxy();

        beanPostProcessor(registry).postProcessAfterInitialization(proxy, "weatherTools");

        assertThat(registry.getAgenticTool("query_weather"))
                .as("CGLIB 代理的工具 Bean 应被注册")
                .isNotNull();
    }

    @Test
    void registersJdkProxiedBean() {
        ToolRegistry registry = new ToolRegistry();
        ProxyFactory proxyFactory = new ProxyFactory(new TimeTools());
        proxyFactory.setProxyTargetClass(false);
        Object proxy = proxyFactory.getProxy();

        beanPostProcessor(registry).postProcessAfterInitialization(proxy, "timeTools");

        assertThat(registry.getAgenticTool("current_time"))
                .as("JDK 动态代理的工具 Bean 应被注册")
                .isNotNull();
    }

    @Test
    void invokesToolThroughCglibProxy() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        ProxyFactory proxyFactory = new ProxyFactory(new WeatherTools());
        proxyFactory.setProxyTargetClass(true);
        Object proxy = proxyFactory.getProxy();

        beanPostProcessor(registry).postProcessAfterInitialization(proxy, "weatherTools");

        String result = registry.getAgenticTool("query_weather").getInvoker().invoke(null, null);
        assertThat(result).isEqualTo("晴");
    }

    @Test
    void invokesToolThroughJdkProxy() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        ProxyFactory proxyFactory = new ProxyFactory(new TimeTools());
        proxyFactory.setProxyTargetClass(false);
        Object proxy = proxyFactory.getProxy();

        beanPostProcessor(registry).postProcessAfterInitialization(proxy, "timeTools");

        String result = registry.getAgenticTool("current_time").getInvoker().invoke(null, null);
        assertThat(result).isEqualTo("12:00");
    }
}
