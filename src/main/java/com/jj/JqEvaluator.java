package com.jj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

import java.math.BigDecimal;
import java.util.*;

public class JqEvaluator {

    private final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node.isBigDecimal()) return node.decimalValue();
        if (node.isBigInteger()) return new BigDecimal(node.bigIntegerValue());
        return new BigDecimal(node.asText());
    }

    private JsonNode numberResult(BigDecimal bd) {
        try {
            if (bd.scale() == 0 || bd.stripTrailingZeros().scale() <= 0) {
                return LongNode.valueOf(bd.longValueExact());
            }
        } catch (ArithmeticException ignored) {
        }
        return DecimalNode.valueOf(bd);
    }

    public JsonNode evaluate(JsonNode root, String expression) {
        if (expression == null || expression.isEmpty() || ".".equals(expression)) {
            return root;
        }
        List<Token> tokens = tokenize(expression);
        ExprNode ast = parse(tokens);
        return eval(ast, root);
    }

    private JsonNode eval(ExprNode expr, JsonNode context) {
        return switch (expr.type) {
            case DOT -> context;
            case DOTDOT -> evalDotDot(expr, context);
            case FIELD_ACCESS -> evalFieldAccess(expr, context);
            case INDEX -> evalIndex(expr, context);
            case SLICE -> evalSlice(expr, context);
            case ARRAY_ITER -> evalArrayIter(expr, context);
            case OBJECT_ITER -> evalObjectIter(expr, context);
            case PIPE -> evalPipe(expr, context);
            case COMMA -> evalComma(expr, context);
            case LITERAL -> expr.value;
            case FUNCTION_CALL -> evalFunction(expr, context);
            case BINARY_OP -> evalBinaryOp(expr, context);
            case ARRAY_CONSTRUCT -> evalArrayConstruct(expr, context);
            case OBJECT_CONSTRUCT -> evalObjectConstruct(expr, context);
            case VARIABLE -> evalVariable(expr, context);
            case NOT -> evalNot(expr, context);
            case UNARY_MINUS -> evalUnaryMinus(expr, context);
            case IF_THEN_ELSE -> evalIfThenElse(expr, context);
        };
    }

    private JsonNode evalDotDot(ExprNode expr, JsonNode context) {
        ArrayNode result = nodeFactory.arrayNode();
        collectAllNodes(context, result);
        return result;
    }

    private void collectAllNodes(JsonNode node, ArrayNode collector) {
        if (node == null || node.isNull()) {
            return;
        }
        collector.add(node);
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectAllNodes(child, collector);
            }
        } else if (node.isObject()) {
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                collectAllNodes(it.next(), collector);
            }
        }
    }

    private JsonNode evalFieldAccess(ExprNode expr, JsonNode context) {
        String fieldName = expr.stringValue;
        JsonNode target = eval(expr.left, context);
        if (target == null || target.isNull()) {
            return NullNode.getInstance();
        }
        if (target.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode item : target) {
                if (item.isObject()) {
                    result.add(item.path(fieldName));
                }
            }
            return result;
        }
        if (target.isObject()) {
            return target.path(fieldName);
        }
        return NullNode.getInstance();
    }

    private JsonNode evalIndex(ExprNode expr, JsonNode context) {
        JsonNode target = eval(expr.left, context);
        JsonNode indexNode = eval(expr.right, context);
        if (target == null || target.isNull()) {
            return NullNode.getInstance();
        }
        if (target.isArray()) {
            if (indexNode.isInt()) {
                int idx = indexNode.intValue();
                int len = target.size();
                if (idx < 0) idx += len;
                if (idx >= 0 && idx < len) {
                    return target.get(idx);
                }
                return NullNode.getInstance();
            }
        }
        if (target.isObject() && indexNode.isTextual()) {
            return target.path(indexNode.textValue());
        }
        return NullNode.getInstance();
    }

    private JsonNode evalSlice(ExprNode expr, JsonNode context) {
        JsonNode target = eval(expr.middle, context);
        if (target == null || !target.isArray()) {
            return NullNode.getInstance();
        }
        int len = target.size();
        Integer start = null;
        Integer end = null;
        if (expr.left != null) {
            JsonNode s = eval(expr.left, context);
            if (s.isInt()) {
                start = s.intValue();
                if (start < 0) start += len;
            }
        }
        if (expr.right != null) {
            JsonNode e = eval(expr.right, context);
            if (e.isInt()) {
                end = e.intValue();
                if (end < 0) end += len;
            }
        }
        if (start == null) start = 0;
        if (end == null) end = len;
        start = Math.max(0, Math.min(len, start));
        end = Math.max(0, Math.min(len, end));
        ArrayNode result = nodeFactory.arrayNode();
        for (int i = start; i < end; i++) {
            result.add(target.get(i));
        }
        return result;
    }

    private JsonNode evalArrayIter(ExprNode expr, JsonNode context) {
        JsonNode target = eval(expr.left, context);
        if (target == null || target.isNull()) {
            return NullNode.getInstance();
        }
        if (target.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode item : target) {
                result.add(item);
            }
            return result;
        }
        if (target.isObject()) {
            ArrayNode result = nodeFactory.arrayNode();
            Iterator<JsonNode> it = target.elements();
            while (it.hasNext()) {
                result.add(it.next());
            }
            return result;
        }
        return target;
    }

    private JsonNode evalObjectIter(ExprNode expr, JsonNode context) {
        JsonNode target = eval(expr.left, context);
        if (target != null && target.isObject()) {
            ArrayNode keys = nodeFactory.arrayNode();
            Iterator<String> it = target.fieldNames();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            return keys;
        }
        return NullNode.getInstance();
    }

    private JsonNode evalPipe(ExprNode expr, JsonNode context) {
        JsonNode left = eval(expr.left, context);
        return eval(expr.right, left);
    }

    private JsonNode evalComma(ExprNode expr, JsonNode context) {
        JsonNode left = eval(expr.left, context);
        JsonNode right = eval(expr.right, context);
        ArrayNode result = nodeFactory.arrayNode();
        if (left.isArray()) {
            for (JsonNode n : left) result.add(n);
        } else {
            result.add(left);
        }
        if (right.isArray()) {
            for (JsonNode n : right) result.add(n);
        } else {
            result.add(right);
        }
        return result;
    }

    private JsonNode evalFunction(ExprNode expr, JsonNode context) {
        String funcName = expr.stringValue;
        return switch (funcName) {
            case "length" -> evalLength(context);
            case "keys" -> evalKeys(context);
            case "keys_unsorted" -> evalKeysUnsorted(context);
            case "select" -> evalSelect(expr, context);
            case "map" -> evalMap(expr, context);
            case "tonumber" -> evalToNumber(context);
            case "tostring" -> evalToString(context);
            case "type" -> evalType(context);
            case "sort" -> evalSort(context);
            case "reverse" -> evalReverse(context);
            case "unique" -> evalUnique(context);
            case "flatten" -> evalFlatten(context);
            case "has" -> evalHas(expr, context);
            case "contains" -> evalContains(expr, context);
            case "startswith" -> evalStartsWith(expr, context);
            case "endswith" -> evalEndsWith(expr, context);
            case "split" -> evalSplit(expr, context);
            case "join" -> evalJoin(expr, context);
            case "explode" -> evalExplode(context);
            case "implode" -> evalImplode(context);
            case "empty" -> NullNode.getInstance();
            case "not" -> evalNotFunction(context);
            case "floor" -> evalFloor(context);
            case "ceil" -> evalCeil(context);
            case "round" -> evalRound(context);
            case "sqrt" -> evalSqrt(context);
            case "add" -> evalAdd(context);
            case "all" -> evalAll(context);
            case "any" -> evalAny(context);
            case "first" -> evalFirst(context);
            case "last" -> evalLast(context);
            case "range" -> evalRange(expr, context);
            case "in" -> evalIn(expr, context);
            case "inside" -> evalInside(expr, context);
            case "del" -> evalDel(expr, context);
            case "path" -> evalPath(expr, context);
            default -> throw new RuntimeException("未知函数: " + funcName);
        };
    }

    private JsonNode evalLength(JsonNode context) {
        if (context == null || context.isNull()) return IntNode.valueOf(0);
        if (context.isArray()) return IntNode.valueOf(context.size());
        if (context.isObject()) return IntNode.valueOf(context.size());
        if (context.isTextual()) return IntNode.valueOf(context.textValue().length());
        if (context.isNumber()) return IntNode.valueOf(context.asText().length());
        return IntNode.valueOf(0);
    }

    private JsonNode evalKeys(JsonNode context) {
        if (context != null && context.isObject()) {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = context.fieldNames();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            ArrayNode result = nodeFactory.arrayNode();
            for (String k : keys) result.add(k);
            return result;
        }
        if (context != null && context.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (int i = 0; i < context.size(); i++) result.add(i);
            return result;
        }
        return NullNode.getInstance();
    }

    private JsonNode evalKeysUnsorted(JsonNode context) {
        if (context != null && context.isObject()) {
            ArrayNode result = nodeFactory.arrayNode();
            Iterator<String> it = context.fieldNames();
            while (it.hasNext()) result.add(it.next());
            return result;
        }
        return evalKeys(context);
    }

    private JsonNode evalSelect(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) {
            return NullNode.getInstance();
        }
        ExprNode condition = expr.args.get(0);
        if (context.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode item : context) {
                JsonNode cond = eval(condition, item);
                if (isTruthy(cond)) {
                    result.add(item);
                }
            }
            return result;
        }
        JsonNode cond = eval(condition, context);
        return isTruthy(cond) ? context : NullNode.getInstance();
    }

    private JsonNode evalMap(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) {
            return context;
        }
        ExprNode mapper = expr.args.get(0);
        if (context.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode item : context) {
                result.add(eval(mapper, item));
            }
            return result;
        }
        return eval(mapper, context);
    }

    private JsonNode evalToNumber(JsonNode context) {
        if (context == null || context.isNull()) return NullNode.getInstance();
        if (context.isNumber()) return context;
        if (context.isTextual()) {
            try {
                String text = context.textValue().trim();
                if (text.contains(".") || text.contains("e") || text.contains("E")) {
                    return DecimalNode.valueOf(new BigDecimal(text));
                }
                try {
                    long l = Long.parseLong(text);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return IntNode.valueOf((int) l);
                    }
                    return LongNode.valueOf(l);
                } catch (NumberFormatException e) {
                    return DecimalNode.valueOf(new BigDecimal(text));
                }
            } catch (NumberFormatException e) {
                throw new RuntimeException("无法转换为数字: " + context.textValue());
            }
        }
        return NullNode.getInstance();
    }

    private JsonNode evalToString(JsonNode context) {
        if (context == null || context.isNull()) return TextNode.valueOf("null");
        if (context.isTextual()) return context;
        return TextNode.valueOf(context.toString());
    }

    private JsonNode evalType(JsonNode context) {
        if (context == null || context.isNull()) return TextNode.valueOf("null");
        if (context.isBoolean()) return TextNode.valueOf("boolean");
        if (context.isNumber()) {
            if (context.isInt() || context.isLong()) return TextNode.valueOf("number");
            if (context.isFloat() || context.isDouble()) return TextNode.valueOf("number");
            if (context.isBigDecimal() || context.isBigInteger()) return TextNode.valueOf("number");
            return TextNode.valueOf("number");
        }
        if (context.isTextual()) return TextNode.valueOf("string");
        if (context.isArray()) return TextNode.valueOf("array");
        if (context.isObject()) return TextNode.valueOf("object");
        return TextNode.valueOf("null");
    }

    private JsonNode evalSort(JsonNode context) {
        if (context == null || !context.isArray()) return context;
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode n : context) list.add(n);
        list.sort(this::compareNodes);
        ArrayNode result = nodeFactory.arrayNode();
        for (JsonNode n : list) result.add(n);
        return result;
    }

    private JsonNode evalReverse(JsonNode context) {
        if (context == null || !context.isArray()) return context;
        ArrayNode result = nodeFactory.arrayNode();
        for (int i = context.size() - 1; i >= 0; i--) {
            result.add(context.get(i));
        }
        return result;
    }

    private JsonNode evalUnique(JsonNode context) {
        if (context == null || !context.isArray()) return context;
        Set<String> seen = new LinkedHashSet<>();
        ArrayNode result = nodeFactory.arrayNode();
        for (JsonNode n : context) {
            String key = n.toString();
            if (seen.add(key)) {
                result.add(n);
            }
        }
        return result;
    }

    private JsonNode evalFlatten(JsonNode context) {
        if (context == null || !context.isArray()) return context;
        ArrayNode result = nodeFactory.arrayNode();
        flattenHelper(context, result);
        return result;
    }

    private void flattenHelper(JsonNode node, ArrayNode result) {
        if (node.isArray()) {
            for (JsonNode n : node) {
                flattenHelper(n, result);
            }
        } else {
            result.add(node);
        }
    }

    private JsonNode evalHas(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty() || context == null) {
            return BooleanNode.FALSE;
        }
        JsonNode key = eval(expr.args.get(0), context);
        if (context.isObject() && key.isTextual()) {
            return BooleanNode.valueOf(context.has(key.textValue()));
        }
        if (context.isArray() && key.isInt()) {
            int idx = key.intValue();
            return BooleanNode.valueOf(idx >= 0 && idx < context.size());
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalContains(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty() || context == null) {
            return BooleanNode.FALSE;
        }
        JsonNode needle = eval(expr.args.get(0), context);
        if (context.isTextual() && needle.isTextual()) {
            return BooleanNode.valueOf(context.textValue().contains(needle.textValue()));
        }
        if (context.isArray()) {
            for (JsonNode item : context) {
                if (nodeEquals(item, needle)) {
                    return BooleanNode.TRUE;
                }
            }
            return BooleanNode.FALSE;
        }
        return BooleanNode.FALSE;
    }

    private boolean nodeEquals(JsonNode a, JsonNode b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private JsonNode evalStartsWith(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return BooleanNode.FALSE;
        JsonNode prefix = eval(expr.args.get(0), context);
        if (context.isTextual() && prefix.isTextual()) {
            return BooleanNode.valueOf(context.textValue().startsWith(prefix.textValue()));
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalEndsWith(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return BooleanNode.FALSE;
        JsonNode suffix = eval(expr.args.get(0), context);
        if (context.isTextual() && suffix.isTextual()) {
            return BooleanNode.valueOf(context.textValue().endsWith(suffix.textValue()));
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalSplit(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return NullNode.getInstance();
        JsonNode sep = eval(expr.args.get(0), context);
        if (context.isTextual() && sep.isTextual()) {
            String[] parts = context.textValue().split(sep.textValue(), -1);
            ArrayNode result = nodeFactory.arrayNode();
            for (String p : parts) result.add(p);
            return result;
        }
        return NullNode.getInstance();
    }

    private JsonNode evalJoin(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return NullNode.getInstance();
        JsonNode sep = eval(expr.args.get(0), context);
        if (context.isArray() && sep.isTextual()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < context.size(); i++) {
                if (i > 0) sb.append(sep.textValue());
                JsonNode item = context.get(i);
                if (item.isTextual()) {
                    sb.append(item.textValue());
                } else {
                    sb.append(item.toString());
                }
            }
            return TextNode.valueOf(sb.toString());
        }
        return NullNode.getInstance();
    }

    private JsonNode evalExplode(JsonNode context) {
        if (context == null || !context.isTextual()) return NullNode.getInstance();
        ArrayNode result = nodeFactory.arrayNode();
        for (char c : context.textValue().toCharArray()) {
            result.add((int) c);
        }
        return result;
    }

    private JsonNode evalImplode(JsonNode context) {
        if (context == null || !context.isArray()) return NullNode.getInstance();
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : context) {
            if (n.isInt()) {
                sb.append((char) n.intValue());
            }
        }
        return TextNode.valueOf(sb.toString());
    }

    private JsonNode evalNotFunction(JsonNode context) {
        return BooleanNode.valueOf(!isTruthy(context));
    }

    private JsonNode evalNot(ExprNode expr, JsonNode context) {
        JsonNode operand = eval(expr.left, context);
        return BooleanNode.valueOf(!isTruthy(operand));
    }

    private JsonNode evalUnaryMinus(ExprNode expr, JsonNode context) {
        JsonNode operand = eval(expr.left, context);
        if (operand.isInt()) return IntNode.valueOf(-operand.intValue());
        if (operand.isLong()) return LongNode.valueOf(-operand.longValue());
        if (operand.isDouble()) return DoubleNode.valueOf(-operand.doubleValue());
        if (operand.isFloat()) return FloatNode.valueOf(-operand.floatValue());
        if (operand.isBigInteger()) return BigIntegerNode.valueOf(operand.bigIntegerValue().negate());
        if (operand.isBigDecimal()) return DecimalNode.valueOf(operand.decimalValue().negate());
        throw new RuntimeException("一元减号不能作用于非数字");
    }

    private JsonNode evalFloor(JsonNode context) {
        if (context == null || !context.isNumber()) return NullNode.getInstance();
        return IntNode.valueOf((int) Math.floor(context.doubleValue()));
    }

    private JsonNode evalCeil(JsonNode context) {
        if (context == null || !context.isNumber()) return NullNode.getInstance();
        return IntNode.valueOf((int) Math.ceil(context.doubleValue()));
    }

    private JsonNode evalRound(JsonNode context) {
        if (context == null || !context.isNumber()) return NullNode.getInstance();
        return IntNode.valueOf((int) Math.round(context.doubleValue()));
    }

    private JsonNode evalSqrt(JsonNode context) {
        if (context == null || !context.isNumber()) return NullNode.getInstance();
        return DoubleNode.valueOf(Math.sqrt(context.doubleValue()));
    }

    private JsonNode evalAdd(JsonNode context) {
        if (context == null || context.isNull()) return IntNode.valueOf(0);
        if (context.isArray()) {
            if (context.size() == 0) return IntNode.valueOf(0);
            JsonNode first = context.get(0);
            if (first.isNumber()) {
                double sum = 0;
                for (JsonNode n : context) {
                    if (n.isNumber()) sum += n.doubleValue();
                }
                return DoubleNode.valueOf(sum);
            }
            if (first.isTextual()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode n : context) {
                    if (n.isTextual()) sb.append(n.textValue());
                }
                return TextNode.valueOf(sb.toString());
            }
            if (first.isArray()) {
                ArrayNode result = nodeFactory.arrayNode();
                for (JsonNode n : context) {
                    if (n.isArray()) {
                        for (JsonNode item : n) result.add(item);
                    }
                }
                return result;
            }
            if (first.isObject()) {
                ObjectNode result = nodeFactory.objectNode();
                for (JsonNode n : context) {
                    if (n.isObject()) {
                        result.setAll((ObjectNode) n);
                    }
                }
                return result;
            }
        }
        return IntNode.valueOf(0);
    }

    private JsonNode evalAll(JsonNode context) {
        if (context == null || !context.isArray()) return BooleanNode.FALSE;
        for (JsonNode n : context) {
            if (!isTruthy(n)) return BooleanNode.FALSE;
        }
        return BooleanNode.TRUE;
    }

    private JsonNode evalAny(JsonNode context) {
        if (context == null || !context.isArray()) return BooleanNode.FALSE;
        for (JsonNode n : context) {
            if (isTruthy(n)) return BooleanNode.TRUE;
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalFirst(JsonNode context) {
        if (context == null || !context.isArray() || context.size() == 0) return NullNode.getInstance();
        return context.get(0);
    }

    private JsonNode evalLast(JsonNode context) {
        if (context == null || !context.isArray() || context.size() == 0) return NullNode.getInstance();
        return context.get(context.size() - 1);
    }

    private JsonNode evalRange(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return NullNode.getInstance();
        ArrayNode result = nodeFactory.arrayNode();
        if (expr.args.size() == 1) {
            JsonNode end = eval(expr.args.get(0), context);
            if (end.isInt()) {
                for (int i = 0; i < end.intValue(); i++) result.add(i);
            }
        } else if (expr.args.size() >= 2) {
            JsonNode start = eval(expr.args.get(0), context);
            JsonNode end = eval(expr.args.get(1), context);
            if (start.isInt() && end.isInt()) {
                for (int i = start.intValue(); i < end.intValue(); i++) result.add(i);
            }
        }
        return result;
    }

    private JsonNode evalIn(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return BooleanNode.FALSE;
        JsonNode container = eval(expr.args.get(0), context);
        if (container.isObject()) {
            if (context.isTextual()) {
                return BooleanNode.valueOf(container.has(context.textValue()));
            }
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalInside(ExprNode expr, JsonNode context) {
        if (expr.args == null || expr.args.isEmpty()) return BooleanNode.FALSE;
        JsonNode container = eval(expr.args.get(0), context);
        if (container.isArray() && container.size() > 0) {
            JsonNode first = container.get(0);
            if (first.isNumber() && context.isNumber()) {
                double val = context.doubleValue();
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                for (JsonNode n : container) {
                    double d = n.doubleValue();
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                return BooleanNode.valueOf(val >= min && val <= max);
            }
        }
        return BooleanNode.FALSE;
    }

    private JsonNode evalDel(ExprNode expr, JsonNode context) {
        return context;
    }

    private JsonNode evalPath(ExprNode expr, JsonNode context) {
        return NullNode.getInstance();
    }

    private JsonNode evalIfThenElse(ExprNode expr, JsonNode context) {
        JsonNode condition = eval(expr.left, context);
        if (isTruthy(condition)) {
            return eval(expr.middle, context);
        } else {
            return eval(expr.right, context);
        }
    }

    private JsonNode evalBinaryOp(ExprNode expr, JsonNode context) {
        String op = expr.stringValue;
        JsonNode left = eval(expr.left, context);
        JsonNode right = eval(expr.right, context);

        return switch (op) {
            case "+" -> addNodes(left, right);
            case "-" -> subtractNodes(left, right);
            case "*" -> multiplyNodes(left, right);
            case "/" -> divideNodes(left, right);
            case "%" -> modNodes(left, right);
            case "==" -> BooleanNode.valueOf(nodeEquals(left, right));
            case "!=" -> BooleanNode.valueOf(!nodeEquals(left, right));
            case "<" -> BooleanNode.valueOf(compareNodes(left, right) < 0);
            case ">" -> BooleanNode.valueOf(compareNodes(left, right) > 0);
            case "<=" -> BooleanNode.valueOf(compareNodes(left, right) <= 0);
            case ">=" -> BooleanNode.valueOf(compareNodes(left, right) >= 0);
            case "and" -> BooleanNode.valueOf(isTruthy(left) && isTruthy(right));
            case "or" -> BooleanNode.valueOf(isTruthy(left) || isTruthy(right));
            case "//" -> isTruthy(left) ? left : right;
            default -> throw new RuntimeException("未知操作符: " + op);
        };
    }

    private JsonNode addNodes(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            if (left.isInt() && right.isInt()) {
                return IntNode.valueOf(left.intValue() + right.intValue());
            }
            if (left.isLong() && right.isLong()) {
                return LongNode.valueOf(left.longValue() + right.longValue());
            }
            return numberResult(toBigDecimal(left).add(toBigDecimal(right)));
        }
        if (left.isTextual() && right.isTextual()) {
            return TextNode.valueOf(left.textValue() + right.textValue());
        }
        if (left.isArray() && right.isArray()) {
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode n : left) result.add(n);
            for (JsonNode n : right) result.add(n);
            return result;
        }
        if (left.isObject() && right.isObject()) {
            ObjectNode result = nodeFactory.objectNode();
            result.setAll((ObjectNode) left);
            result.setAll((ObjectNode) right);
            return result;
        }
        throw new RuntimeException("不能相加: " + left + " + " + right);
    }

    private JsonNode subtractNodes(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            if (left.isInt() && right.isInt()) {
                return IntNode.valueOf(left.intValue() - right.intValue());
            }
            if (left.isLong() && right.isLong()) {
                return LongNode.valueOf(left.longValue() - right.longValue());
            }
            return numberResult(toBigDecimal(left).subtract(toBigDecimal(right)));
        }
        if (left.isArray() && right.isArray()) {
            Set<String> remove = new HashSet<>();
            for (JsonNode n : right) remove.add(n.toString());
            ArrayNode result = nodeFactory.arrayNode();
            for (JsonNode n : left) {
                if (!remove.contains(n.toString())) result.add(n);
            }
            return result;
        }
        throw new RuntimeException("不能相减");
    }

    private JsonNode multiplyNodes(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            if (left.isInt() && right.isInt()) {
                return IntNode.valueOf(left.intValue() * right.intValue());
            }
            if (left.isLong() && right.isLong()) {
                return LongNode.valueOf(left.longValue() * right.longValue());
            }
            return numberResult(toBigDecimal(left).multiply(toBigDecimal(right)));
        }
        if (left.isTextual() && right.isInt()) {
            StringBuilder sb = new StringBuilder();
            int times = right.intValue();
            for (int i = 0; i < times; i++) sb.append(left.textValue());
            return TextNode.valueOf(sb.toString());
        }
        if (left.isInt() && right.isTextual()) {
            StringBuilder sb = new StringBuilder();
            int times = left.intValue();
            for (int i = 0; i < times; i++) sb.append(right.textValue());
            return TextNode.valueOf(sb.toString());
        }
        throw new RuntimeException("不能相乘");
    }

    private JsonNode divideNodes(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            BigDecimal d = toBigDecimal(left).divide(toBigDecimal(right), 34, java.math.RoundingMode.HALF_UP);
            return numberResult(d);
        }
        throw new RuntimeException("不能相除");
    }

    private JsonNode modNodes(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            BigDecimal d = toBigDecimal(left).remainder(toBigDecimal(right));
            return numberResult(d);
        }
        throw new RuntimeException("不能取模");
    }

    private int compareNodes(JsonNode a, JsonNode b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a.isNull() && b.isNull()) return 0;
        if (a.isNull()) return -1;
        if (b.isNull()) return 1;

        if (a.isBoolean() && b.isBoolean()) {
            return Boolean.compare(a.booleanValue(), b.booleanValue());
        }
        if (a.isNumber() && b.isNumber()) {
            return toBigDecimal(a).compareTo(toBigDecimal(b));
        }
        if (a.isTextual() && b.isTextual()) {
            return a.textValue().compareTo(b.textValue());
        }
        if (a.isArray() && b.isArray()) {
            int minLen = Math.min(a.size(), b.size());
            for (int i = 0; i < minLen; i++) {
                int c = compareNodes(a.get(i), b.get(i));
                if (c != 0) return c;
            }
            return Integer.compare(a.size(), b.size());
        }
        if (a.isObject() && b.isObject()) {
            return Integer.compare(a.size(), b.size());
        }
        return 0;
    }

    private boolean isTruthy(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return !toBigDecimal(node).equals(BigDecimal.ZERO);
        if (node.isTextual()) return !node.textValue().isEmpty();
        if (node.isArray()) return true;
        if (node.isObject()) return true;
        return false;
    }

    private JsonNode evalArrayConstruct(ExprNode expr, JsonNode context) {
        ArrayNode result = nodeFactory.arrayNode();
        if (expr.left != null) {
            JsonNode val = eval(expr.left, context);
            if (val.isArray()) {
                for (JsonNode n : val) result.add(n);
            } else {
                result.add(val);
            }
        }
        return result;
    }

    private JsonNode evalObjectConstruct(ExprNode expr, JsonNode context) {
        return NullNode.getInstance();
    }

    private JsonNode evalVariable(ExprNode expr, JsonNode context) {
        return context;
    }

    enum TokenType {
        DOT, DOTDOT, LBRACKET, RBRACKET, LBRACE, RBRACE, LPAREN, RPAREN,
        IDENTIFIER, STRING, NUMBER, PIPE, COMMA, COLON, SEMICOLON,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        EQ, NEQ, LT, GT, LE, GE,
        AND, OR, NOT,
        QUESTION,
        IF, THEN, ELSE, END,
        EOF
    }

    static class Token {
        TokenType type;
        String value;
        int pos;

        Token(TokenType type, String value, int pos) {
            this.type = type;
            this.value = value;
            this.pos = pos;
        }

        @Override
        public String toString() {
            return type + "(" + value + ")";
        }
    }

    private List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = expr.length();
        while (i < len) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '.' && i + 1 < len && expr.charAt(i + 1) == '.') {
                tokens.add(new Token(TokenType.DOTDOT, "..", i));
                i += 2;
            } else if (c == '.') {
                tokens.add(new Token(TokenType.DOT, ".", i));
                i++;
            } else if (c == '[') {
                tokens.add(new Token(TokenType.LBRACKET, "[", i));
                i++;
            } else if (c == ']') {
                tokens.add(new Token(TokenType.RBRACKET, "]", i));
                i++;
            } else if (c == '{') {
                tokens.add(new Token(TokenType.LBRACE, "{", i));
                i++;
            } else if (c == '}') {
                tokens.add(new Token(TokenType.RBRACE, "}", i));
                i++;
            } else if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "(", i));
                i++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")", i));
                i++;
            } else if (c == '|') {
                tokens.add(new Token(TokenType.PIPE, "|", i));
                i++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ",", i));
                i++;
            } else if (c == ':') {
                tokens.add(new Token(TokenType.COLON, ":", i));
                i++;
            } else if (c == ';') {
                tokens.add(new Token(TokenType.SEMICOLON, ";", i));
                i++;
            } else if (c == '?') {
                tokens.add(new Token(TokenType.QUESTION, "?", i));
                i++;
            } else if (c == '+' ) {
                tokens.add(new Token(TokenType.PLUS, "+", i));
                i++;
            } else if (c == '-') {
                tokens.add(new Token(TokenType.MINUS, "-", i));
                i++;
            } else if (c == '*') {
                tokens.add(new Token(TokenType.STAR, "*", i));
                i++;
            } else if (c == '/' && i + 1 < len && expr.charAt(i + 1) == '/') {
                tokens.add(new Token(TokenType.OR, "//", i));
                i += 2;
            } else if (c == '/') {
                tokens.add(new Token(TokenType.SLASH, "/", i));
                i++;
            } else if (c == '%') {
                tokens.add(new Token(TokenType.PERCENT, "%", i));
                i++;
            } else if (c == '=' && i + 1 < len && expr.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.EQ, "==", i));
                i += 2;
            } else if (c == '!' && i + 1 < len && expr.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.NEQ, "!=", i));
                i += 2;
            } else if (c == '<' && i + 1 < len && expr.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.LE, "<=", i));
                i += 2;
            } else if (c == '<') {
                tokens.add(new Token(TokenType.LT, "<", i));
                i++;
            } else if (c == '>' && i + 1 < len && expr.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.GE, ">=", i));
                i += 2;
            } else if (c == '>') {
                tokens.add(new Token(TokenType.GT, ">", i));
                i++;
            } else if (c == '!' || (c == 'n' && i + 2 < len && expr.startsWith("not", i))) {
                tokens.add(new Token(TokenType.NOT, "not", i));
                i += 3;
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                char quote = c;
                i++;
                while (i < len && expr.charAt(i) != quote) {
                    if (expr.charAt(i) == '\\' && i + 1 < len) {
                        char next = expr.charAt(i + 1);
                        switch (next) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case '\\': sb.append('\\'); break;
                            case '"': sb.append('"'); break;
                            case '\'': sb.append('\''); break;
                            default: sb.append(next); break;
                        }
                        i += 2;
                    } else {
                        sb.append(expr.charAt(i));
                        i++;
                    }
                }
                tokens.add(new Token(TokenType.STRING, sb.toString(), i));
                i++;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < len && Character.isDigit(expr.charAt(i + 1)))) {
                StringBuilder sb = new StringBuilder();
                if (c == '-') {
                    sb.append(c);
                    i++;
                }
                while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.' ||
                        expr.charAt(i) == 'e' || expr.charAt(i) == 'E' ||
                        expr.charAt(i) == '+' || expr.charAt(i) == '-')) {
                    sb.append(expr.charAt(i));
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, sb.toString(), i));
            } else if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isLetterOrDigit(expr.charAt(i)) ||
                        expr.charAt(i) == '_' || expr.charAt(i) == '$' || expr.charAt(i) == '@')) {
                    sb.append(expr.charAt(i));
                    i++;
                }
                String word = sb.toString();
                TokenType type;
                switch (word) {
                    case "and": type = TokenType.AND; break;
                    case "or": type = TokenType.OR; break;
                    case "not": type = TokenType.NOT; break;
                    case "if": type = TokenType.IF; break;
                    case "then": type = TokenType.THEN; break;
                    case "else": type = TokenType.ELSE; break;
                    case "end": type = TokenType.END; break;
                    default: type = TokenType.IDENTIFIER; break;
                }
                tokens.add(new Token(type, word, i));
            } else {
                throw new RuntimeException("意外字符 '" + c + "' 在位置 " + i);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", len));
        return tokens;
    }

    enum ExprType {
        DOT, DOTDOT, FIELD_ACCESS, INDEX, SLICE, ARRAY_ITER, OBJECT_ITER,
        PIPE, COMMA, LITERAL, FUNCTION_CALL, BINARY_OP,
        ARRAY_CONSTRUCT, OBJECT_CONSTRUCT, VARIABLE,
        NOT, UNARY_MINUS, IF_THEN_ELSE
    }

    static class ExprNode {
        ExprType type;
        ExprNode left;
        ExprNode right;
        ExprNode middle;
        JsonNode value;
        String stringValue;
        List<ExprNode> args;

        ExprNode(ExprType type) {
            this.type = type;
        }

        static ExprNode literal(JsonNode value) {
            ExprNode n = new ExprNode(ExprType.LITERAL);
            n.value = value;
            return n;
        }

        static ExprNode fieldAccess(String name) {
            ExprNode n = new ExprNode(ExprType.FIELD_ACCESS);
            n.stringValue = name;
            return n;
        }

        static ExprNode binaryOp(String op, ExprNode left, ExprNode right) {
            ExprNode n = new ExprNode(ExprType.BINARY_OP);
            n.stringValue = op;
            n.left = left;
            n.right = right;
            return n;
        }

        static ExprNode functionCall(String name, List<ExprNode> args) {
            ExprNode n = new ExprNode(ExprType.FUNCTION_CALL);
            n.stringValue = name;
            n.args = args;
            return n;
        }
    }

    private static class ParserState {
        List<Token> tokens;
        int pos;

        ParserState(List<Token> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }

        Token peek() {
            return tokens.get(pos);
        }

        Token consume() {
            return tokens.get(pos++);
        }

        Token consume(TokenType type, String expected) {
            Token t = tokens.get(pos);
            if (t.type != type) {
                throw new RuntimeException("期望 " + expected + " 但得到 " + t.type + " 在位置 " + t.pos);
            }
            pos++;
            return t;
        }

        boolean matches(TokenType type) {
            return peek().type == type;
        }

        boolean eof() {
            return peek().type == TokenType.EOF;
        }
    }

    private ExprNode parse(List<Token> tokens) {
        ParserState state = new ParserState(tokens);
        ExprNode expr = parseComma(state);
        if (!state.eof()) {
            Token t = state.peek();
            throw new RuntimeException("意外 token " + t.type + " 在位置 " + t.pos);
        }
        return expr;
    }

    private ExprNode parseComma(ParserState state) {
        ExprNode left = parsePipe(state);
        if (state.matches(TokenType.COMMA)) {
            state.consume();
            ExprNode right = parseComma(state);
            ExprNode n = new ExprNode(ExprType.COMMA);
            n.left = left;
            n.right = right;
            return n;
        }
        return left;
    }

    private ExprNode parsePipe(ParserState state) {
        ExprNode left = parseIfThenElse(state);
        if (state.matches(TokenType.PIPE)) {
            state.consume();
            ExprNode right = parsePipe(state);
            ExprNode n = new ExprNode(ExprType.PIPE);
            n.left = left;
            n.right = right;
            return n;
        }
        return left;
    }

    private ExprNode parseIfThenElse(ParserState state) {
        if (state.matches(TokenType.IF)) {
            state.consume();
            ExprNode cond = parseComma(state);
            state.consume(TokenType.THEN, "'then'");
            ExprNode thenBranch = parseComma(state);
            state.consume(TokenType.ELSE, "'else'");
            ExprNode elseBranch = parseComma(state);
            state.consume(TokenType.END, "'end'");
            ExprNode n = new ExprNode(ExprType.IF_THEN_ELSE);
            n.left = cond;
            n.middle = thenBranch;
            n.right = elseBranch;
            return n;
        }
        return parseOr(state);
    }

    private ExprNode parseOr(ParserState state) {
        ExprNode left = parseAnd(state);
        while (state.matches(TokenType.OR)) {
            state.consume();
            ExprNode right = parseAnd(state);
            left = ExprNode.binaryOp("or", left, right);
        }
        return left;
    }

    private ExprNode parseAnd(ParserState state) {
        ExprNode left = parseComparison(state);
        while (state.matches(TokenType.AND)) {
            state.consume();
            ExprNode right = parseComparison(state);
            left = ExprNode.binaryOp("and", left, right);
        }
        return left;
    }

    private ExprNode parseComparison(ParserState state) {
        ExprNode left = parseAddSub(state);
        while (state.matches(TokenType.EQ) || state.matches(TokenType.NEQ) ||
                state.matches(TokenType.LT) || state.matches(TokenType.GT) ||
                state.matches(TokenType.LE) || state.matches(TokenType.GE)) {
            Token op = state.consume();
            ExprNode right = parseAddSub(state);
            left = ExprNode.binaryOp(op.value, left, right);
        }
        return left;
    }

    private ExprNode parseAddSub(ParserState state) {
        ExprNode left = parseMulDiv(state);
        while (state.matches(TokenType.PLUS) || state.matches(TokenType.MINUS)) {
            Token op = state.consume();
            ExprNode right = parseMulDiv(state);
            left = ExprNode.binaryOp(op.value, left, right);
        }
        return left;
    }

    private ExprNode parseMulDiv(ParserState state) {
        ExprNode left = parseUnary(state);
        while (state.matches(TokenType.STAR) || state.matches(TokenType.SLASH) ||
                state.matches(TokenType.PERCENT)) {
            Token op = state.consume();
            ExprNode right = parseUnary(state);
            left = ExprNode.binaryOp(op.value, left, right);
        }
        return left;
    }

    private ExprNode parseUnary(ParserState state) {
        if (state.matches(TokenType.MINUS)) {
            state.consume();
            ExprNode operand = parseUnary(state);
            ExprNode n = new ExprNode(ExprType.UNARY_MINUS);
            n.left = operand;
            return n;
        }
        if (state.matches(TokenType.NOT)) {
            state.consume();
            ExprNode operand = parseUnary(state);
            ExprNode n = new ExprNode(ExprType.NOT);
            n.left = operand;
            return n;
        }
        return parsePostfix(state);
    }

    private ExprNode parsePostfix(ParserState state) {
        ExprNode expr = parsePrimary(state);
        while (true) {
            if (state.matches(TokenType.DOT)) {
                state.consume();
                if (state.matches(TokenType.IDENTIFIER)) {
                    Token id = state.consume();
                    ExprNode fa = ExprNode.fieldAccess(id.value);
                    fa.left = expr;
                    expr = fa;
                } else if (state.matches(TokenType.LBRACKET)) {
                    state.consume();
                    if (state.matches(TokenType.RBRACKET)) {
                        state.consume();
                        ExprNode iter = new ExprNode(ExprType.ARRAY_ITER);
                        iter.left = expr;
                        expr = iter;
                    } else {
                        ExprNode indexExpr = parseComma(state);
                        if (state.matches(TokenType.COLON)) {
                            state.consume();
                            ExprNode endExpr = null;
                            if (!state.matches(TokenType.RBRACKET)) {
                                endExpr = parseComma(state);
                            }
                            state.consume(TokenType.RBRACKET, "']'");
                            ExprNode slice = new ExprNode(ExprType.SLICE);
                            slice.left = indexExpr;
                            slice.right = endExpr;
                            slice.middle = expr;
                            expr = slice;
                        } else {
                            state.consume(TokenType.RBRACKET, "']'");
                            ExprNode idx = new ExprNode(ExprType.INDEX);
                            idx.left = expr;
                            idx.right = indexExpr;
                            expr = idx;
                        }
                    }
                } else if (state.matches(TokenType.STRING)) {
                    Token s = state.consume();
                    ExprNode fa = ExprNode.fieldAccess(s.value);
                    fa.left = expr;
                    expr = fa;
                } else if (state.matches(TokenType.NUMBER)) {
                    Token num = state.consume();
                    ExprNode idx = new ExprNode(ExprType.INDEX);
                    idx.left = expr;
                    idx.right = parseNumberLiteral(num);
                    expr = idx;
                } else {
                    break;
                }
            } else if (state.matches(TokenType.LBRACKET)) {
                state.consume();
                if (state.matches(TokenType.QUESTION)) {
                    state.consume();
                    state.consume(TokenType.RBRACKET, "']'");
                    ExprNode iter = new ExprNode(ExprType.ARRAY_ITER);
                    iter.left = expr;
                    expr = iter;
                } else if (state.matches(TokenType.RBRACKET)) {
                    state.consume();
                    ExprNode iter = new ExprNode(ExprType.ARRAY_ITER);
                    iter.left = expr;
                    expr = iter;
                } else if (state.matches(TokenType.COLON)) {
                    state.consume();
                    ExprNode endExpr = null;
                    if (!state.matches(TokenType.RBRACKET)) {
                        endExpr = parseComma(state);
                    }
                    state.consume(TokenType.RBRACKET, "']'");
                    ExprNode slice = new ExprNode(ExprType.SLICE);
                    slice.left = null;
                    slice.right = endExpr;
                    slice.middle = expr;
                    expr = slice;
                } else {
                    ExprNode indexExpr = parseComma(state);
                    if (state.matches(TokenType.COLON)) {
                        state.consume();
                        ExprNode endExpr = null;
                        if (!state.matches(TokenType.RBRACKET)) {
                            endExpr = parseComma(state);
                        }
                        state.consume(TokenType.RBRACKET, "']'");
                        ExprNode slice = new ExprNode(ExprType.SLICE);
                        slice.left = indexExpr;
                        slice.right = endExpr;
                        slice.middle = expr;
                        expr = slice;
                    } else {
                        state.consume(TokenType.RBRACKET, "']'");
                        ExprNode idx = new ExprNode(ExprType.INDEX);
                        idx.left = expr;
                        idx.right = indexExpr;
                        expr = idx;
                    }
                }
            } else if (state.matches(TokenType.LPAREN)) {
                state.consume();
                if (state.matches(TokenType.RPAREN)) {
                    state.consume();
                    String funcName;
                    if (expr.type == ExprType.FIELD_ACCESS) {
                        funcName = expr.stringValue;
                    } else if (expr.type == ExprType.FUNCTION_CALL) {
                        funcName = expr.stringValue;
                    } else if (expr.type == ExprType.DOT) {
                        throw new RuntimeException("语法错误: 不能对 '.' 调用函数");
                    } else {
                        throw new RuntimeException("语法错误: 函数调用形式不正确");
                    }
                    expr = ExprNode.functionCall(funcName, new ArrayList<>());
                } else {
                    List<ExprNode> args = new ArrayList<>();
                    args.add(parseComma(state));
                    while (state.matches(TokenType.SEMICOLON) || state.matches(TokenType.COMMA)) {
                        state.consume();
                        args.add(parseComma(state));
                    }
                    state.consume(TokenType.RPAREN, "')'");
                    String funcName;
                    if (expr.type == ExprType.FIELD_ACCESS) {
                        funcName = expr.stringValue;
                    } else if (expr.type == ExprType.FUNCTION_CALL) {
                        funcName = expr.stringValue;
                    } else {
                        throw new RuntimeException("语法错误: 期望函数名");
                    }
                    expr = ExprNode.functionCall(funcName, args);
                }
            } else if (state.matches(TokenType.LBRACE)) {
                expr = parseObjectConstruct(state, expr);
            } else {
                break;
            }
        }
        return expr;
    }

    private ExprNode parsePrimary(ParserState state) {
        Token t = state.peek();
        return switch (t.type) {
            case DOT -> {
                state.consume();
                ExprNode dotNode = new ExprNode(ExprType.DOT);
                yield parseDotSuffixes(state, dotNode);
            }
            case DOTDOT -> {
                state.consume();
                yield new ExprNode(ExprType.DOTDOT);
            }
            case STRING -> {
                Token s = state.consume();
                yield ExprNode.literal(TextNode.valueOf(s.value));
            }
            case NUMBER -> {
                Token num = state.consume();
                yield parseNumberLiteral(num);
            }
            case IDENTIFIER -> {
                Token id = state.consume();
                String idVal = id.value;
                if (idVal.equals("null")) {
                    yield ExprNode.literal(NullNode.getInstance());
                } else if (idVal.equals("true")) {
                    yield ExprNode.literal(BooleanNode.TRUE);
                } else if (idVal.equals("false")) {
                    yield ExprNode.literal(BooleanNode.FALSE);
                } else if (idVal.equals("@")) {
                    yield new ExprNode(ExprType.VARIABLE);
                } else if (idVal.startsWith("$")) {
                    ExprNode varNode = new ExprNode(ExprType.VARIABLE);
                    varNode.stringValue = idVal;
                    yield varNode;
                } else {
                    yield ExprNode.functionCall(idVal, new ArrayList<>());
                }
            }
            case LPAREN -> {
                state.consume();
                ExprNode expr = parseComma(state);
                state.consume(TokenType.RPAREN, "')'");
                yield expr;
            }
            case LBRACKET -> {
                state.consume();
                ExprNode inner = null;
                if (!state.matches(TokenType.RBRACKET)) {
                    inner = parseComma(state);
                }
                state.consume(TokenType.RBRACKET, "']'");
                ExprNode n = new ExprNode(ExprType.ARRAY_CONSTRUCT);
                n.left = inner;
                yield n;
            }
            default -> throw new RuntimeException("意外 token " + t.type + " 在位置 " + t.pos);
        };
    }

    private ExprNode parseDotSuffixes(ParserState state, ExprNode expr) {
        if (state.matches(TokenType.IDENTIFIER)) {
            Token id = state.consume();
            ExprNode fa = ExprNode.fieldAccess(id.value);
            fa.left = expr;
            return fa;
        }
        if (state.matches(TokenType.STRING)) {
            Token s = state.consume();
            ExprNode fa = ExprNode.fieldAccess(s.value);
            fa.left = expr;
            return fa;
        }
        if (state.matches(TokenType.LBRACKET)) {
            state.consume();
            if (state.matches(TokenType.QUESTION)) {
                state.consume();
                state.consume(TokenType.RBRACKET, "']'");
                ExprNode iter = new ExprNode(ExprType.ARRAY_ITER);
                iter.left = expr;
                return iter;
            } else if (state.matches(TokenType.RBRACKET)) {
                state.consume();
                ExprNode iter = new ExprNode(ExprType.ARRAY_ITER);
                iter.left = expr;
                return iter;
            } else if (state.matches(TokenType.COLON)) {
                state.consume();
                ExprNode endExpr = null;
                if (!state.matches(TokenType.RBRACKET)) {
                    endExpr = parseComma(state);
                }
                state.consume(TokenType.RBRACKET, "']'");
                ExprNode slice = new ExprNode(ExprType.SLICE);
                slice.left = null;
                slice.right = endExpr;
                slice.middle = expr;
                return slice;
            } else {
                ExprNode indexExpr = parseComma(state);
                if (state.matches(TokenType.COLON)) {
                    state.consume();
                    ExprNode endExpr = null;
                    if (!state.matches(TokenType.RBRACKET)) {
                        endExpr = parseComma(state);
                    }
                    state.consume(TokenType.RBRACKET, "']'");
                    ExprNode slice = new ExprNode(ExprType.SLICE);
                    slice.left = indexExpr;
                    slice.right = endExpr;
                    slice.middle = expr;
                    return slice;
                } else {
                    state.consume(TokenType.RBRACKET, "']'");
                    ExprNode idx = new ExprNode(ExprType.INDEX);
                    idx.left = expr;
                    idx.right = indexExpr;
                    return idx;
                }
            }
        }
        return expr;
    }

    private ExprNode parseNumberLiteral(Token num) {
        String s = num.value;
        try {
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return ExprNode.literal(DecimalNode.valueOf(new BigDecimal(s)));
            }
            long l = Long.parseLong(s);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return ExprNode.literal(IntNode.valueOf((int) l));
            }
            return ExprNode.literal(LongNode.valueOf(l));
        } catch (NumberFormatException e) {
            return ExprNode.literal(DecimalNode.valueOf(new BigDecimal(s)));
        }
    }

    private ExprNode parseObjectConstruct(ParserState state, ExprNode expr) {
        state.consume(TokenType.LBRACE, "'{'");
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        if (!state.matches(TokenType.RBRACE)) {
            parseObjectEntry(state, obj, expr);
            while (state.matches(TokenType.COMMA)) {
                state.consume();
                parseObjectEntry(state, obj, expr);
            }
        }
        state.consume(TokenType.RBRACE, "'}'");
        return ExprNode.literal(obj);
    }

    private void parseObjectEntry(ParserState state, ObjectNode obj, ExprNode context) {
        Token keyToken = state.consume();
        String key;
        if (keyToken.type == TokenType.IDENTIFIER || keyToken.type == TokenType.STRING) {
            key = keyToken.value;
        } else {
            throw new RuntimeException("期望字段名，得到 " + keyToken.type);
        }
        state.consume(TokenType.COLON, "':'");
        obj.put(key, "<placeholder>");
    }
}
