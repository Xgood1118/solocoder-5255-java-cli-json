package com.jj;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

public class PathEngine {

    private final Configuration config;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public PathEngine() {
        this.config = Configuration.builder()
                .jsonProvider(new JacksonJsonNodeJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .build();
    }

    public JsonNode evaluate(JsonNode root, String expression) {
        if (expression == null || expression.isEmpty() || "$".equals(expression)) {
            return root;
        }
        try {
            DocumentContext ctx = JsonPath.using(config).parse(root.toString());
            Object result = ctx.read(expression);
            if (result instanceof JsonNode) {
                return (JsonNode) result;
            }
            return mapper.valueToTree(result);
        } catch (Exception e) {
            throw new RuntimeException("JSONPath 求值失败: " + e.getMessage(), e);
        }
    }
}
