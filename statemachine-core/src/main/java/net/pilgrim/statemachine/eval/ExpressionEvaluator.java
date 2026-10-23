package net.pilgrim.statemachine.eval;

import net.pilgrim.statemachine.model.Event;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight, zero-dependency expression evaluator for conditions ({@code cond="..."})
 * and expressions ({@code expr="..."}).
 *
 * Supports arithmetic, string concatenation, comparisons (==, !=, &lt;, &lt;=, &gt;, &gt;=),
 * logical operators (&amp;&amp;, ||, !), ternary (?:), literals, and dotted variable lookups
 * such as {@code event$.values.orderId} or {@code event$.status}.
 */
public class ExpressionEvaluator {

    @SafeVarargs
    public static boolean evaluateCondition(String cond, Map<String, Object>... scopes) {
        if (cond == null || cond.isBlank()) {
            return true;
        }
        Object result = evaluate(cond, scopes);
        return isTruthy(result);
    }

    @SafeVarargs
    public static Object evaluate(String expr, Map<String, Object>... scopes) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        Parser parser = new Parser(expr.trim(), scopes);
        return parser.parseExpression();
    }

    public static boolean isTruthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
        if (val instanceof String s) return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
        return true;
    }

    private static class Parser {
        private final String input;
        private final Map<String, Object>[] scopes;
        private int pos = 0;

        Parser(String input, Map<String, Object>[] scopes) {
            this.input = input;
            this.scopes = scopes;
        }

        Object parseExpression() {
            Object result = parseTernary();
            skipWhitespace();
            return result;
        }

        private Object parseTernary() {
            Object cond = parseLogicalOr();
            skipWhitespace();
            if (match('?')) {
                Object trueVal = parseTernary();
                skipWhitespace();
                if (match(':')) {
                    Object falseVal = parseTernary();
                    return isTruthy(cond) ? trueVal : falseVal;
                }
                return isTruthy(cond) ? trueVal : null;
            }
            return cond;
        }

        private Object parseLogicalOr() {
            Object left = parseLogicalAnd();
            while (true) {
                skipWhitespace();
                if (match("||")) {
                    Object right = parseLogicalAnd();
                    left = isTruthy(left) || isTruthy(right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseLogicalAnd() {
            Object left = parseEquality();
            while (true) {
                skipWhitespace();
                if (match("&&")) {
                    Object right = parseEquality();
                    left = isTruthy(left) && isTruthy(right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseEquality() {
            Object left = parseRelational();
            while (true) {
                skipWhitespace();
                if (match("===") || match("==")) {
                    Object right = parseRelational();
                    left = looseEquals(left, right);
                } else if (match("!==") || match("!=")) {
                    Object right = parseRelational();
                    left = !looseEquals(left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseRelational() {
            Object left = parseAdditive();
            while (true) {
                skipWhitespace();
                if (match("<=")) {
                    Object right = parseAdditive();
                    left = compare(left, right) <= 0;
                } else if (match(">=")) {
                    Object right = parseAdditive();
                    left = compare(left, right) >= 0;
                } else if (match('<')) {
                    Object right = parseAdditive();
                    left = compare(left, right) < 0;
                } else if (match('>')) {
                    Object right = parseAdditive();
                    left = compare(left, right) > 0;
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseAdditive() {
            Object left = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    Object right = parseMultiplicative();
                    left = add(left, right);
                } else if (match('-')) {
                    Object right = parseMultiplicative();
                    left = subtract(left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseMultiplicative() {
            Object left = parseUnary();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    Object right = parseUnary();
                    left = toDouble(left) * toDouble(right);
                } else if (match('/')) {
                    Object right = parseUnary();
                    double divisor = toDouble(right);
                    left = divisor == 0 ? Double.POSITIVE_INFINITY : toDouble(left) / divisor;
                } else if (match('%')) {
                    Object right = parseUnary();
                    left = toDouble(left) % toDouble(right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseUnary() {
            skipWhitespace();
            if (match('!')) {
                return !isTruthy(parseUnary());
            }
            if (match('-')) {
                return -toDouble(parseUnary());
            }
            if (match('+')) {
                return toDouble(parseUnary());
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                return null;
            }

            char c = input.charAt(pos);

            // Parentheses
            if (c == '(') {
                pos++;
                Object result = parseTernary();
                skipWhitespace();
                match(')');
                return result;
            }

            // String literals: '...' or "..."
            if (c == '\'' || c == '"') {
                return parseStringLiteral(c);
            }

            // Number literals
            if (Character.isDigit(c) || (c == '.' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                return parseNumberLiteral();
            }

            // Identifiers / keywords: true, false, null, undefined, or variable names
            if (Character.isJavaIdentifierStart(c)) {
                return parseIdentifier();
            }

            pos++;
            return null;
        }

        private String parseStringLiteral(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char ch = input.charAt(pos++);
                if (ch == '\\' && pos < input.length()) {
                    char escaped = input.charAt(pos++);
                    if (escaped == 'n') sb.append('\n');
                    else if (escaped == 't') sb.append('\t');
                    else sb.append(escaped);
                } else if (ch == quote) {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private Object parseNumberLiteral() {
            int start = pos;
            boolean hasDot = false;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isDigit(ch)) {
                    pos++;
                } else if (ch == '.' && !hasDot) {
                    hasDot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = input.substring(start, pos);
            try {
                if (hasDot) {
                    return Double.parseDouble(numStr);
                } else {
                    long val = Long.parseLong(numStr);
                    if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                        return (int) val;
                    }
                    return val;
                }
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        private Object parseIdentifier() {
            int start = pos;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            String ident = input.substring(start, pos);

            if ("true".equalsIgnoreCase(ident)) return true;
            if ("false".equalsIgnoreCase(ident)) return false;
            if ("null".equalsIgnoreCase(ident) || "undefined".equalsIgnoreCase(ident)) return null;

            return resolveVariable(ident);
        }

        private Object resolveVariable(String path) {
            if (scopes == null) return null;

            // Direct match first
            for (Map<String, Object> scope : scopes) {
                if (scope != null && scope.containsKey(path)) {
                    return scope.get(path);
                }
            }

            // Dotted resolution: e.g. event$.values.target or session.id
            if (path.contains(".")) {
                String[] parts = path.split("\\.");
                Object current = null;
                for (Map<String, Object> scope : scopes) {
                    if (scope != null && scope.containsKey(parts[0])) {
                        current = scope.get(parts[0]);
                        break;
                    }
                }
                if (current == null) return null;

                for (int i = 1; i < parts.length; i++) {
                    current = resolveProperty(current, parts[i]);
                    if (current == null) {
                        return null;
                    }
                }
                return current;
            }

            return null;
        }

        private Object resolveProperty(Object obj, String propName) {
            if (obj == null) return null;
            if (obj instanceof Event event) {
                return event.getProperty(propName);
            }
            if (obj instanceof Map<?, ?> map) {
                if (map.containsKey(propName)) {
                    return map.get(propName);
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (String.valueOf(entry.getKey()).equalsIgnoreCase(propName)) {
                        return entry.getValue();
                    }
                }
                return null;
            }
            try {
                String getter = "get" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
                Method method = obj.getClass().getMethod(getter);
                return method.invoke(obj);
            } catch (Exception ignored) {
                try {
                    String isGetter = "is" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
                    Method method = obj.getClass().getMethod(isGetter);
                    return method.invoke(obj);
                } catch (Exception ignored2) {
                    return null;
                }
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(char c) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean match(String s) {
            skipWhitespace();
            if (input.startsWith(s, pos)) {
                pos += s.length();
                return true;
            }
            return false;
        }

        private Object add(Object left, Object right) {
            if (left instanceof String || right instanceof String) {
                return stringify(left) + stringify(right);
            }
            if (left instanceof Double || right instanceof Double) {
                return toDouble(left) + toDouble(right);
            }
            return toLong(left) + toLong(right);
        }

        private Object subtract(Object left, Object right) {
            if (left instanceof Double || right instanceof Double) {
                return toDouble(left) - toDouble(right);
            }
            return toLong(left) - toLong(right);
        }

        private boolean looseEquals(Object left, Object right) {
            if (left == right) return true;
            if (left == null || right == null) return false;
            if (left instanceof Number && right instanceof Number) {
                return Double.compare(toDouble(left), toDouble(right)) == 0;
            }
            if (left instanceof Boolean || right instanceof Boolean) {
                return isTruthy(left) == isTruthy(right);
            }
            return Objects.equals(stringify(left), stringify(right));
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number && right instanceof Number) {
                return Double.compare(toDouble(left), toDouble(right));
            }
            return stringify(left).compareTo(stringify(right));
        }

        private double toDouble(Object val) {
            if (val == null) return 0.0;
            if (val instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(val.toString().trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        private long toLong(Object val) {
            if (val == null) return 0L;
            if (val instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(val.toString().trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        private String stringify(Object val) {
            return val == null ? "" : val.toString();
        }
    }
}
