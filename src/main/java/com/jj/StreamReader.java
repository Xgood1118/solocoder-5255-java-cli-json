package com.jj;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

public class StreamReader {

    private final ObjectMapper mapper;
    private final JsonFactory jsonFactory;

    public StreamReader() {
        this.mapper = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.jsonFactory = new JsonFactory();
    }

    @FunctionalInterface
    public interface LineHandler {
        void handle(JsonNode node, int lineNumber);
    }

    public void processStream(InputStream inputStream, LineHandler handler) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(trimmed);
                    handler.handle(node, lineNumber);
                } catch (Exception e) {
                    throw new RuntimeException("第 " + lineNumber + " 行解析失败: " + e.getMessage(), e);
                }
            }
        }
    }

    public void processJsonArrayStream(InputStream inputStream, LineHandler handler) throws Exception {
        try (JsonParser parser = jsonFactory.createParser(inputStream)) {
            parser.setCodec(mapper);
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new RuntimeException("期望 JSON 数组开头");
            }
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode node = parser.readValueAsTree();
                handler.handle(node, index);
                index++;
            }
        }
    }

    public long countLines(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            long count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
            return count;
        }
    }

    public long countJsonArrayItems(InputStream inputStream) throws Exception {
        try (JsonParser parser = jsonFactory.createParser(inputStream)) {
            long count = 0;
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new RuntimeException("期望 JSON 数组开头");
            }
            int depth = 1;
            while (depth > 0 && parser.nextToken() != null) {
                token = parser.currentToken();
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                    depth++;
                } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
                    depth--;
                    if (depth == 1 && token == JsonToken.END_OBJECT) {
                        count++;
                    }
                    if (depth == 0) {
                        break;
                    }
                } else if (depth == 2) {
                    if (token != null && token.isScalarValue()) {
                        count++;
                    }
                }
            }
            return count;
        }
    }
}
