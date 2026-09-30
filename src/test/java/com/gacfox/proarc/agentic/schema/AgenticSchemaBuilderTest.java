package com.gacfox.proarc.agentic.schema;

import com.gacfox.proarc.agentic.model.openai.Parameters;
import com.gacfox.proarc.agentic.model.openai.Property;
import com.gacfox.proarc.agentic.model.openai.Tool;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticSchemaBuilderTest {
    private final AgenticSchemaBuilder builder = new AgenticSchemaBuilder();

    enum Color {
        RED, GREEN
    }

    @Data
    static class ScalarDto {
        @AgenticToolParam(name = "s", description = "字符串")
        private String s;
        @AgenticToolParam(name = "i", description = "整型")
        private int i;
        @AgenticToolParam(name = "l", description = "长整型", required = false)
        private Long l;
        @AgenticToolParam(name = "d", description = "浮点", required = false)
        private Double d;
        @AgenticToolParam(name = "b", description = "布尔", required = false)
        private Boolean b;
        @AgenticToolParam(name = "e", description = "枚举", required = false)
        private Color e;
        @AgenticToolParam(name = "bd", description = "高精度小数", required = false)
        private BigDecimal bd;
        @AgenticToolParam(name = "u", description = "UUID", required = false)
        private UUID u;
    }

    @Data
    static class Inner {
        @AgenticToolParam(name = "x", description = "嵌套字段")
        private String x;
    }

    @Data
    static class Outer {
        @AgenticToolParam(name = "inner", description = "嵌套对象")
        private Inner inner;
    }

    @Data
    static class CollectionDto {
        @AgenticToolParam(name = "tags", description = "字符串列表")
        private List<String> tags;
        @AgenticToolParam(name = "ids", description = "字符串数组")
        private String[] ids;
        @AgenticToolParam(name = "items", description = "对象列表")
        private List<Inner> items;
    }

    @Data
    static class MapDto {
        @AgenticToolParam(name = "m", description = "Map字段")
        private Map<String, String> m;
    }

    static class NoAnnotationDto {
        @SuppressWarnings("unused")
        private String x;
    }

    static class BlankNameDto {
        @AgenticToolParam(name = "", description = "空名称")
        private String x;
    }

    static class StaticFieldDto {
        @SuppressWarnings("unused")
        static String CONSTANT = "a";
        @AgenticToolParam(name = "x", description = "普通字段")
        private String x;
    }

    @Data
    static class SelfRefNode {
        @AgenticToolParam(name = "next", description = "下一个节点", required = false)
        private SelfRefNode next;
    }

    @Data
    static class MutualA {
        @AgenticToolParam(name = "b", description = "B对象", required = false)
        private MutualB b;
    }

    @Data
    static class MutualB {
        @AgenticToolParam(name = "a", description = "A对象", required = false)
        private MutualA a;
    }

    @Data
    static class TreeNode {
        @AgenticToolParam(name = "children", description = "子节点", required = false)
        private List<TreeNode> children;
    }

    @Data
    static class SiblingReuse {
        @AgenticToolParam(name = "first", description = "第一个")
        private Inner first;
        @AgenticToolParam(name = "second", description = "第二个")
        private Inner second;
    }

    @Data
    static class Menu {
        @AgenticToolParam(name = "name", description = "菜单名")
        private String name;
        @AgenticToolParam(name = "children", description = "子菜单，最多3层", required = false, maxDepth = 3)
        private List<Menu> children;
    }

    @Test
    void mapsScalarTypes() {
        Parameters params = builder.buildParameters(ScalarDto.class);

        assertThat(params.getType()).isEqualTo("object");
        assertThat(params.getProperties().get("s").getType()).isEqualTo("string");
        assertThat(params.getProperties().get("i").getType()).isEqualTo("integer");
        assertThat(params.getProperties().get("l").getType()).isEqualTo("integer");
        assertThat(params.getProperties().get("d").getType()).isEqualTo("number");
        assertThat(params.getProperties().get("b").getType()).isEqualTo("boolean");
        assertThat(params.getProperties().get("e").getType()).isEqualTo("string");
        assertThat(params.getProperties().get("bd").getType()).isEqualTo("number");
        assertThat(params.getProperties().get("u").getType()).isEqualTo("string");
        assertThat(params.getProperties().get("s").getDescription()).isEqualTo("字符串");
    }

    @Test
    void collectsRequiredFieldsInDeclarationOrder() {
        Parameters params = builder.buildParameters(ScalarDto.class);
        assertThat(params.getRequired()).containsExactly("s", "i");
    }

    @Test
    void buildsNestedDtoAsObject() {
        Property inner = builder.buildParameters(Outer.class).getProperties().get("inner");

        assertThat(inner.getType()).isEqualTo("object");
        assertThat(inner.getDescription()).isEqualTo("嵌套对象");
        assertThat(inner.getProperties().get("x").getType()).isEqualTo("string");
        assertThat(inner.getRequired()).containsExactly("x");
    }

    @Test
    void buildsCollectionAndArrayProperties() {
        Parameters params = builder.buildParameters(CollectionDto.class);

        Property tags = params.getProperties().get("tags");
        assertThat(tags.getType()).isEqualTo("array");
        assertThat(tags.getItems().getType()).isEqualTo("string");

        Property ids = params.getProperties().get("ids");
        assertThat(ids.getType()).isEqualTo("array");
        assertThat(ids.getItems().getType()).isEqualTo("string");

        Property items = params.getProperties().get("items");
        assertThat(items.getType()).isEqualTo("array");
        assertThat(items.getItems().getType()).isEqualTo("object");
        assertThat(items.getItems().getProperties().get("x").getType()).isEqualTo("string");
    }

    @Test
    void rejectsMapType() {
        assertThatThrownBy(() -> builder.buildParameters(MapDto.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Map");
    }

    @Test
    void rejectsFieldWithoutAnnotation() {
        assertThatThrownBy(() -> builder.buildParameters(NoAnnotationDto.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@AgenticToolParam");
    }

    @Test
    void rejectsBlankParamName() {
        assertThatThrownBy(() -> builder.buildParameters(BlankNameDto.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void skipsStaticFields() {
        Parameters params = builder.buildParameters(StaticFieldDto.class);
        assertThat(params.getProperties()).containsOnlyKeys("x");
    }

    @Test
    void nullRootProducesEmptyObject() {
        Parameters params = builder.buildParameters(null);

        assertThat(params.getType()).isEqualTo("object");
        assertThat(params.getProperties()).isEmpty();
        assertThat(params.getRequired()).isEmpty();
    }

    @Test
    void rejectsNonDtoRootType() {
        assertThatThrownBy(() -> builder.buildParameters(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DTO");
    }

    @Test
    void buildsToolDefinition() {
        Tool tool = builder.buildTool("query_weather", "查询天气", Inner.class);

        assertThat(tool.getType()).isEqualTo("function");
        assertThat(tool.getFunction().getName()).isEqualTo("query_weather");
        assertThat(tool.getFunction().getDescription()).isEqualTo("查询天气");
        assertThat(tool.getFunction().getParameters().getProperties()).containsKey("x");
    }

    @Test
    void directSelfReferenceThrows() {
        assertThatThrownBy(() -> builder.buildParameters(SelfRefNode.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular DTO reference")
                .hasMessageContaining("SelfRefNode -> SelfRefNode")
                .hasMessageContaining("maxDepth");
    }

    @Test
    void mutualReferenceThrowsWithFullChain() {
        assertThatThrownBy(() -> builder.buildParameters(MutualA.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MutualA -> MutualB -> MutualA");
    }

    @Test
    void selfReferenceThroughCollectionThrows() {
        assertThatThrownBy(() -> builder.buildParameters(TreeNode.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular DTO reference");
    }

    @Test
    void siblingReuseOfSameTypeIsAllowed() {
        Parameters params = builder.buildParameters(SiblingReuse.class);

        assertThat(params.getProperties()).containsOnlyKeys("first", "second");
        assertThat(params.getProperties().get("first").getProperties()).containsKey("x");
        assertThat(params.getProperties().get("second").getProperties()).containsKey("x");
    }

    @Test
    void boundedRecursionExpandsToMaxDepthThenOmitsField() {
        Parameters params = builder.buildParameters(Menu.class);

        // 根Menu为第1层，maxDepth=3允许Menu在路径上出现3次
        Property level1 = params.getProperties().get("children");
        assertThat(level1.getType()).isEqualTo("array");
        assertThat(level1.getItems().getType()).isEqualTo("object");
        assertThat(level1.getItems().getProperties()).containsOnlyKeys("name", "children");

        Property level2 = level1.getItems().getProperties().get("children");
        assertThat(level2).isNotNull();

        // 第3层Menu不再包含children字段，模型无法生成第4层
        Property deepestMenu = level2.getItems();
        assertThat(deepestMenu.getProperties()).containsOnlyKeys("name");
        assertThat(deepestMenu.getRequired()).containsExactly("name");
    }
}
