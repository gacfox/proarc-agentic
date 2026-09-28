package com.gacfox.proarc.agentic.schema;

import com.gacfox.proarc.agentic.model.openai.Function;
import com.gacfox.proarc.agentic.model.openai.Parameters;
import com.gacfox.proarc.agentic.model.openai.Property;
import com.gacfox.proarc.agentic.model.openai.Tool;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agentic工具和结构化输出共用的JSON Schema构建器。
 */
public class AgenticSchemaBuilder {

    /**
     * 构建OpenAI工具定义。
     *
     * @param toolName    工具名
     * @param description 工具描述
     * @param dtoType     参数DTO类型；为null时生成空object
     * @return 工具定义
     */
    public Tool buildTool(String toolName, String description, Class<?> dtoType) {
        return Tool.builder()
                .function(Function.builder()
                        .name(toolName)
                        .description(description)
                        .parameters(buildParameters(dtoType))
                        .build())
                .build();
    }

    /**
     * 构建根参数schema。根节点永远是object。
     *
     * @param dtoType DTO类型；为null时生成空object
     * @return 参数schema
     */
    public Parameters buildParameters(Class<?> dtoType) {
        if (dtoType == null) {
            return Parameters.builder()
                    .properties(new LinkedHashMap<>())
                    .required(List.of())
                    .build();
        }
        if (!isDtoType(dtoType)) {
            throw new IllegalArgumentException("Root schema type must be a DTO: " + dtoType.getName());
        }

        Property root = buildObjectProperty(dtoType, null);
        return Parameters.builder()
                .properties(root.getProperties())
                .required(root.getRequired())
                .build();
    }

    private Property buildProperty(Type type, AgenticToolParam annotation) {
        if (type instanceof GenericArrayType genericArrayType) {
            return Property.builder()
                    .type("array")
                    .description(annotation != null ? annotation.description() : null)
                    .items(buildProperty(genericArrayType.getGenericComponentType(), null))
                    .build();
        }

        Class<?> rawType = rawClass(type);
        if (rawType == null) {
            throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
        }

        String scalarType = resolveScalarJsonType(rawType);
        if (scalarType != null) {
            return Property.builder()
                    .type(scalarType)
                    .description(annotation != null ? annotation.description() : null)
                    .build();
        }
        if (rawType.isArray()) {
            return Property.builder()
                    .type("array")
                    .description(annotation != null ? annotation.description() : null)
                    .items(buildProperty(rawType.getComponentType(), null))
                    .build();
        }
        if (Collection.class.isAssignableFrom(rawType)) {
            Type itemType = resolveCollectionItemType(type);
            return Property.builder()
                    .type("array")
                    .description(annotation != null ? annotation.description() : null)
                    .items(buildProperty(itemType, null))
                    .build();
        }
        if (Map.class.isAssignableFrom(rawType)) {
            throw new IllegalArgumentException("Map type is not supported in agentic schema: " + type.getTypeName());
        }
        if (isDtoType(rawType)) {
            return buildObjectProperty(rawType, annotation);
        }
        throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
    }

    private Property buildObjectProperty(Class<?> dtoType, AgenticToolParam annotation) {
        Map<String, Property> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Field field : dtoType.getDeclaredFields()) {
            if (shouldSkip(field)) {
                continue;
            }
            AgenticToolParam fieldAnnotation = field.getAnnotation(AgenticToolParam.class);
            if (fieldAnnotation == null) {
                throw new IllegalArgumentException("DTO field must be annotated with @AgenticToolParam: "
                        + dtoType.getName() + "." + field.getName());
            }
            if (!StringUtils.hasText(fieldAnnotation.name())) {
                throw new IllegalArgumentException("@AgenticToolParam name must not be blank: "
                        + dtoType.getName() + "." + field.getName());
            }
            properties.put(fieldAnnotation.name(), buildProperty(field.getGenericType(), fieldAnnotation));
            if (fieldAnnotation.required()) {
                required.add(fieldAnnotation.name());
            }
        }

        return Property.builder()
                .type("object")
                .description(annotation != null ? annotation.description() : null)
                .properties(properties)
                .required(required)
                .build();
    }

    private boolean shouldSkip(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                || Modifier.isTransient(modifiers)
                || field.isSynthetic();
    }

    private String resolveScalarJsonType(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class || type.isEnum()
                || Date.class.isAssignableFrom(type)
                || UUID.class == type
                || TemporalAccessor.class.isAssignableFrom(type)) {
            return "string";
        }
        if (type == Integer.class || type == int.class
                || type == Long.class || type == long.class
                || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == BigDecimal.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return null;
    }

    private Type resolveCollectionItemType(Type type) {
        if (!(type instanceof ParameterizedType parameterizedType)) {
            throw new IllegalArgumentException("Collection item type must be declared: " + type.getTypeName());
        }
        Type itemType = parameterizedType.getActualTypeArguments()[0];
        if (itemType instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length == 1 && upperBounds[0] != Object.class) {
                return upperBounds[0];
            }
            throw new IllegalArgumentException("Wildcard collection item type is not supported: " + type.getTypeName());
        }
        return itemType;
    }

    private Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof GenericArrayType) {
            return null;
        }
        return null;
    }

    private boolean isDtoType(Class<?> type) {
        return !type.isPrimitive()
                && !type.isEnum()
                && !type.getName().startsWith("java.");
    }
}
