package com.jj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    public static class ValidationError {
        private final String schemaPath;
        private final String dataPath;
        private final String message;

        public ValidationError(String schemaPath, String dataPath, String message) {
            this.schemaPath = schemaPath;
            this.dataPath = dataPath;
            this.message = message;
        }

        public String getSchemaPath() {
            return schemaPath;
        }

        public String getDataPath() {
            return dataPath;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (dataPath != null && !dataPath.isEmpty()) {
                sb.append("数据路径: ").append(dataPath).append(" | ");
            }
            if (schemaPath != null && !schemaPath.isEmpty()) {
                sb.append("Schema 路径: ").append(schemaPath).append(" | ");
            }
            sb.append("错误: ").append(message);
            return sb.toString();
        }
    }

    public List<ValidationError> validate(JsonNode schemaNode, JsonNode dataNode) {
        List<ValidationError> errors = new ArrayList<>();
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            JsonSchema schema = factory.getSchema(schemaNode);
            Set<ValidationMessage> messages = schema.validate(dataNode);
            for (ValidationMessage msg : messages) {
                String schemaPath = "";
                if (msg.getSchemaLocation() != null) {
                    schemaPath = msg.getSchemaLocation().toString();
                }
                String dataPath = msg.getInstanceLocation() != null ?
                        msg.getInstanceLocation().toString() : "";
                String message = msg.getMessage();
                errors.add(new ValidationError(schemaPath, dataPath, message));
            }
        } catch (Exception e) {
            errors.add(new ValidationError("", "", "Schema 校验异常: " + e.getMessage()));
        }
        return errors;
    }

    public List<ValidationError> validate(String schemaJson, String dataJson) {
        try {
            JsonNode schemaNode = mapper.readTree(schemaJson);
            JsonNode dataNode = mapper.readTree(dataJson);
            return validate(schemaNode, dataNode);
        } catch (Exception e) {
            List<ValidationError> errors = new ArrayList<>();
            errors.add(new ValidationError("", "", "解析 JSON 失败: " + e.getMessage()));
            return errors;
        }
    }
}
