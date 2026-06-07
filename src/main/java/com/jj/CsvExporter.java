package com.jj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CsvExporter {

    public String export(JsonNode data) {
        if (data == null || data.isNull()) {
            return "";
        }

        List<Map<String, String>> flatRows = new ArrayList<>();

        if (data.isArray()) {
            for (JsonNode item : data) {
                Map<String, String> row = new LinkedHashMap<>();
                flattenJson(item, "", row);
                flatRows.add(row);
            }
        } else if (data.isObject()) {
            Map<String, String> row = new LinkedHashMap<>();
            flattenJson(data, "", row);
            flatRows.add(row);
        } else {
            return data.asText() + "\n";
        }

        if (flatRows.isEmpty()) {
            return "";
        }

        Set<String> allKeys = new LinkedHashSet<>();
        for (Map<String, String> row : flatRows) {
            allKeys.addAll(row.keySet());
        }

        List<String> headers = new ArrayList<>(allKeys);

        StringBuilder sb = new StringBuilder();

        sb.append(joinCsvLine(headers)).append('\n');

        for (Map<String, String> row : flatRows) {
            List<String> values = new ArrayList<>();
            for (String header : headers) {
                values.add(row.getOrDefault(header, ""));
            }
            sb.append(joinCsvLine(values)).append('\n');
        }

        return sb.toString();
    }

    private void flattenJson(JsonNode node, String prefix, Map<String, String> result) {
        if (node == null || node.isNull()) {
            result.put(prefix, "");
            return;
        }

        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String newPrefix = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
                flattenJson(node.get(fieldName), newPrefix, result);
            }
        } else if (node.isArray()) {
            if (node.size() == 0) {
                result.put(prefix, "[]");
                return;
            }

            boolean allSimple = true;
            for (JsonNode item : node) {
                if (item.isObject() || item.isArray()) {
                    allSimple = false;
                    break;
                }
            }

            if (allSimple) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : node) {
                    items.add(item.asText());
                }
                result.put(prefix, String.join(";", items));
            } else {
                throw new RuntimeException("嵌套数组无法转换为 CSV: " + prefix);
            }
        } else {
            result.put(prefix, node.asText());
        }
    }

    private String joinCsvLine(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(values.get(i)));
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        boolean needsQuoting = value.contains(",") || value.contains("\"") ||
                value.contains("\n") || value.contains("\r");

        if (!needsQuoting) {
            return value;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (char c : value.toCharArray()) {
            if (c == '"') {
                sb.append("\"\"");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
