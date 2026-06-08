package com.jj;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "jj",
        versionProvider = Main.ManifestVersionProvider.class,
        description = "高性能 JSON 命令行处理工具 - 支持 jq 语法、JSONPath、Schema 校验、流式处理",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "查询表达式 (jq 语法或 JSONPath)", defaultValue = ".")
    private String expression;

    @Parameters(index = "1..*", description = "输入 JSON 文件 (省略则从 stdin 读取)")
    private List<File> inputFiles;

    @Option(names = {"--schema"}, description = "JSON Schema 文件，用于校验输入")
    private File schemaFile;

    @Option(names = {"--format"}, description = "输出格式: json (默认), csv, compact")
    private String format = "json";

    @Option(names = {"--compact"}, description = "紧凑输出 (单行)")
    private boolean compact;

    @Option(names = {"--stream"}, description = "流式处理 JSONL (每行一个 JSON)")
    private boolean stream;

    @Option(names = {"--jsonpath"}, description = "强制使用 JSONPath 模式 (默认自动检测)")
    private boolean useJsonPath;

    @Option(names = {"--indent"}, description = "缩进空格数 (默认 2)")
    private int indent = 2;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final JqEvaluator jqEvaluator = new JqEvaluator();
    private final PathEngine pathEngine = new PathEngine();
    private final SchemaValidator schemaValidator = new SchemaValidator();
    private final CsvExporter csvExporter = new CsvExporter();
    private final StreamReader streamReader = new StreamReader();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            if (schemaFile != null) {
                return doSchemaValidation();
            }

            if (stream) {
                return doStreamProcessing();
            }

            JsonNode combined = loadInput();
            if (combined == null) {
                System.err.println("错误: 没有输入数据");
                return 1;
            }

            JsonNode result = evaluateExpression(combined);

            outputResult(result);
            return 0;
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            return 1;
        }
    }

    private JsonNode loadInput() throws IOException {
        List<JsonNode> nodes = new ArrayList<>();

        if (inputFiles != null && !inputFiles.isEmpty()) {
            for (File file : inputFiles) {
                Path path = file.toPath();
                byte[] bytes = Files.readAllBytes(path);
                JsonNode node = mapper.readTree(bytes);
                nodes.add(node);
            }
        } else {
            String content = readStdin();
            if (content != null && !content.trim().isEmpty()) {
                JsonNode node = mapper.readTree(content);
                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            return null;
        }
        if (nodes.size() == 1) {
            return nodes.get(0);
        }
        ArrayNode array = mapper.createArrayNode();
        for (JsonNode n : nodes) {
            array.add(n);
        }
        return array;
    }

    private JsonNode evaluateExpression(JsonNode input) throws Exception {
        if (".".equals(expression) || expression == null || expression.isEmpty()) {
            return input;
        }

        boolean isJsonPath = useJsonPath || isJsonPathExpression(expression);

        if (isJsonPath) {
            return pathEngine.evaluate(input, expression);
        } else {
            return jqEvaluator.evaluate(input, expression);
        }
    }

    private boolean isJsonPathExpression(String expr) {
        if (expr == null || expr.isEmpty()) return false;
        String trimmed = expr.trim();
        if (trimmed.startsWith("$")) return true;
        if (trimmed.startsWith("$.") || trimmed.startsWith("$[")) return true;
        return false;
    }

    private void outputResult(JsonNode result) throws IOException {
        if ("csv".equalsIgnoreCase(format)) {
            String csv = csvExporter.export(result);
            System.out.print(csv);
            return;
        }

        boolean useCompact = compact || "compact".equalsIgnoreCase(format);

        if (useCompact) {
            System.out.println(mapper.writeValueAsString(result));
        } else {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }

    private int doSchemaValidation() throws Exception {
        JsonNode schemaNode = mapper.readTree(schemaFile);
        List<SchemaValidator.ValidationError> errors = new ArrayList<>();
        List<File> filesToValidate = new ArrayList<>();

        if (inputFiles != null && !inputFiles.isEmpty()) {
            filesToValidate.addAll(inputFiles);
        } else if (expression != null && !expression.equals(".")) {
            File exprFile = new File(expression);
            if (exprFile.exists() && exprFile.isFile()) {
                filesToValidate.add(exprFile);
            }
        }

        if (filesToValidate.isEmpty()) {
            String content = readStdin();
            if (content == null || content.trim().isEmpty()) {
                System.err.println("错误: 没有输入数据");
                return 1;
            }
            JsonNode data = mapper.readTree(content);
            errors = schemaValidator.validate(schemaNode, data);
            if (!errors.isEmpty()) {
                System.err.println("校验失败:");
                for (SchemaValidator.ValidationError e : errors) {
                    System.err.println("  " + e);
                }
            }
        } else {
            for (File file : filesToValidate) {
                JsonNode data = mapper.readTree(file);
                List<SchemaValidator.ValidationError> fileErrors =
                        schemaValidator.validate(schemaNode, data);
                errors.addAll(fileErrors);
                if (!fileErrors.isEmpty()) {
                    System.err.println("文件 " + file.getName() + " 校验失败:");
                    for (SchemaValidator.ValidationError e : fileErrors) {
                        System.err.println("  " + e);
                    }
                }
            }
        }

        if (errors.isEmpty()) {
            System.out.println("Schema 校验通过");
            return 0;
        } else {
            System.err.println("共 " + errors.size() + " 个错误");
            return 1;
        }
    }

    private int doStreamProcessing() throws Exception {
        if (inputFiles != null && !inputFiles.isEmpty()) {
            for (File file : inputFiles) {
                processStreamFile(file);
            }
        } else {
            streamReader.processStream(System.in, this::processOneLine);
        }
        return 0;
    }

    private void processStreamFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            streamReader.processStream(fis, this::processOneLine);
        }
    }

    private void processOneLine(JsonNode node, int lineNumber) {
        try {
            JsonNode result = evaluateExpression(node);
            if (compact || "compact".equalsIgnoreCase(format)) {
                System.out.println(mapper.writeValueAsString(result));
            } else {
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            }
        } catch (Exception e) {
            System.err.println("第 " + lineNumber + " 行处理错误: " + e.getMessage());
        }
    }

    private String readStdin() throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    static class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            Package pkg = Main.class.getPackage();
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            if (version == null) {
                version = "1.0.0 (开发版)";
            }
            return new String[]{"jj version " + version};
        }
    }
}
