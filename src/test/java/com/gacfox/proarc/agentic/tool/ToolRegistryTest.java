package com.gacfox.proarc.agentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacfox.proarc.agentic.agent.AgentContext;
import lombok.Data;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Data
    static class WeatherQuery {
        @AgenticToolParam(name = "city", description = "城市名")
        private String city;
    }

    static class WeatherTools {
        @AgenticTool(name = "query_weather", description = "查询天气")
        public String queryWeather(@AgenticToolParam(name = "query", description = "查询条件") WeatherQuery query) {
            return query.getCity() + "：晴，26℃";
        }

        @AgenticTool(name = "current_time", description = "获取当前时间")
        public String currentTime() {
            return "12:00";
        }

        @AgenticTool(name = "with_context", description = "携带上下文")
        public String withContext(@AgenticToolParam(name = "query", description = "查询条件") WeatherQuery query,
                                  AgentContext ctx) {
            return query.getCity() + "|" + ctx.getVariables().get("tenant");
        }

        @AgenticTool(name = "private_tool", description = "私有方法工具")
        private String privateTool() {
            return "private-ok";
        }
    }

    static class InvalidTools {
        @AgenticTool(name = "no_annotation", description = "参数缺注解")
        public String noAnnotation(String city) {
            return city;
        }

        @AgenticTool(name = "two_dtos", description = "两个DTO参数")
        public String twoDtos(@AgenticToolParam(name = "a", description = "a") WeatherQuery a,
                              @AgenticToolParam(name = "b", description = "b") WeatherQuery b) {
            return "";
        }
    }

    @Test
    void registersAnnotatedToolAndInvokesWithJsonArguments() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        ToolDefinition tool = registry.getAgenticTool("query_weather");
        assertThat(tool).isNotNull();
        assertThat(tool.getDescription()).isEqualTo("查询天气");

        String result = tool.getInvoker().invoke("{\"city\":\"北京\"}", AgentContext.builder().build());
        assertThat(result).isEqualTo("北京：晴，26℃");
    }

    @Test
    void supportsNoArgTool() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        ToolDefinition tool = registry.getAgenticTool("current_time");
        assertThat(tool.getInvoker().invoke(null, AgentContext.builder().build())).isEqualTo("12:00");
    }

    @Test
    void supportsAgentContextParameter() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        AgentContext ctx = AgentContext.builder().build();
        ctx.getVariables().put("tenant", "acme");

        String result = registry.getAgenticTool("with_context").getInvoker().invoke("{\"city\":\"上海\"}", ctx);
        assertThat(result).isEqualTo("上海|acme");
    }

    @Test
    void supportsPrivateToolMethod() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        assertThat(registry.getAgenticTool("private_tool").getInvoker().invoke(null, AgentContext.builder().build()))
                .isEqualTo("private-ok");
    }

    @Test
    void generatesJsonSchemaWithPropertiesAndRequired() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        JsonNode schema = MAPPER.readTree(registry.getAgenticTool("query_weather").getJsonSchema());
        assertThat(schema.at("/function/name").asText()).isEqualTo("query_weather");
        assertThat(schema.at("/function/description").asText()).isEqualTo("查询天气");
        assertThat(schema.at("/function/parameters/type").asText()).isEqualTo("object");
        assertThat(schema.at("/function/parameters/properties/city/type").asText()).isEqualTo("string");
        assertThat(schema.at("/function/parameters/required").toString()).contains("city");
    }

    @Test
    void rejectsDuplicateToolName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());

        assertThatThrownBy(() -> registry.register(new WeatherTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate agentic tool name");
    }

    @Test
    void rejectsDtoParamWithoutAnnotation() {
        ToolRegistry registry = new ToolRegistry();
        InvalidTools tools = new InvalidTools();

        assertThatThrownBy(() -> registry.register(tools))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMultipleDtoParams() {
        ToolRegistry registry = new ToolRegistry();
        InvalidTools tools = new InvalidTools();

        assertThatThrownBy(() -> registry.register(tools))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unregisterRemovesTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTools());
        assertThat(registry.getAllTools()).hasSize(4);

        registry.unregister("query_weather");
        assertThat(registry.getAgenticTool("query_weather")).isNull();
        assertThat(registry.getAllTools()).hasSize(3);
    }

    @Test
    void returnsNullForUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        assertThat(registry.getAgenticTool("missing")).isNull();
        assertThat(registry.getAllTools()).isEmpty();
    }
}
