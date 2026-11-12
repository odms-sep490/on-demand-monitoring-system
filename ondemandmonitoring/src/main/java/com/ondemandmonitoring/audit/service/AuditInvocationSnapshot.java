package com.ondemandmonitoring.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.audit.enums.AuditAction;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Builds bounded, redacted request/result snapshots for the generic business audit aspect.
 * Inputs are recorded as operation context; results are recorded separately from them.
 */
@Component
public class AuditInvocationSnapshot {
    private static final int MAX_DEPTH = 6;
    private static final int MAX_ITEMS = 500;
    private static final int MAX_STRING_LENGTH = 4000;
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            ".*(password|secret|token|authorization|cookie|credential|signature|private.?key|access.?key|api.?key|s3.?key|payment.?url|payment.?link|signed.?url|presigned|s3.?url|card.?number|cvv).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_VALUE = Pattern.compile(
            "^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");
    private static final Set<String> CRITICAL_SERVICE_PACKAGES = Set.of(
            "com.ondemandmonitoring.auth.service",
            "com.ondemandmonitoring.checklist.service",
            "com.ondemandmonitoring.delivery.service",
            "com.ondemandmonitoring.device.service",
            "com.ondemandmonitoring.finance.service",
            "com.ondemandmonitoring.flightpermit.service",
            "com.ondemandmonitoring.mission.service",
            "com.ondemandmonitoring.order.service",
            "com.ondemandmonitoring.pricing.service",
            "com.ondemandmonitoring.service.service",
            "com.ondemandmonitoring.stafflicense.service",
            "com.ondemandmonitoring.user.service");
    private static final List<String> MUTATION_PREFIXES = List.of(
            "create", "add", "register", "clone", "fork", "update", "edit", "save", "upsert",
            "delete", "remove", "purge", "approve", "reject", "accept", "decline", "review",
            "publish", "cancel", "assign", "unassign", "replace", "start", "stop", "complete",
            "fail", "request", "confirm", "finalize", "revoke", "suspend", "activate",
            "deactivate", "enable", "disable", "issue", "expire", "refresh", "reconcile",
            "settle", "refund", "mark", "clear", "retry", "prepare", "process", "submit",
            "close", "open", "resolve", "respond", "release", "attach", "detach", "lock",
            "unlock", "consume", "sync", "synchronize", "amend", "renew", "migrate",
            "restore", "archive", "extend", "change", "transition", "handover", "connect",
            "disconnect", "schedule", "escalate", "reassign", "select", "report",
            "record", "switch", "return");

    private final ObjectMapper objectMapper;

    public AuditInvocationSnapshot(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isCriticalBusinessMutation(Class<?> serviceType, Method method) {
        if (isReadOnlyTransaction(serviceType, method)) return false;

        String packageName = serviceType.getPackageName();
        boolean criticalService = CRITICAL_SERVICE_PACKAGES.stream()
                .anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
        if (!criticalService) return false;

        String name = method.getName();
        if (!isFinanceService(serviceType) && name.toLowerCase(Locale.ROOT).contains("status")) return false;
        return MUTATION_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static boolean isReadOnlyTransaction(Class<?> serviceType, Method method) {
        Transactional transaction = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        if (transaction == null) {
            transaction = AnnotatedElementUtils.findMergedAnnotation(serviceType, Transactional.class);
        }
        return transaction != null && transaction.readOnly();
    }

    private static boolean isFinanceService(Class<?> serviceType) {
        String packageName = serviceType.getPackageName();
        String financePackage = "com.ondemandmonitoring.finance.service";
        return packageName.equals(financePackage) || packageName.startsWith(financePackage + ".");
    }

    public AuditAction action(Method method) {
        String name = method.getName();
        if (startsWithAny(name, "delete", "remove", "purge", "unassign", "detach")) {
            return AuditAction.DELETE;
        }
        if (startsWithAny(name, "create", "add", "register", "clone", "fork", "submit", "issue", "attach")) {
            return AuditAction.CREATE;
        }
        if (startsWithAny(name, "approve", "accept", "publish", "review")) {
            return AuditAction.APPROVE;
        }
        if (startsWithAny(name, "update", "edit", "save", "upsert")) {
            return AuditAction.UPDATE;
        }
        return AuditAction.STATUS_CHANGE;
    }

    public String entityType(Class<?> serviceType) {
        String type = serviceType.getSimpleName()
                .replaceFirst("(?:ServiceImpl|Service|Impl)$", "");
        if (type.isBlank()) {
            Package classPackage = serviceType.getPackage();
            type = classPackage == null ? "business" : classPackage.getName();
        }
        String snake = type.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .toLowerCase(Locale.ROOT);
        return snake.length() <= 80 ? snake : snake.substring(0, 80);
    }

    public String operation(Class<?> serviceType, Method method) {
        return serviceType.getSimpleName() + "." + method.getName();
    }

    public String entityId(Class<?> serviceType, Method method, Object[] arguments, Object result) {
        String fromResult = findIdentifier(result, null, 0, new IdentityHashMap<>());
        if (hasText(fromResult)) return fromResult;

        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < arguments.length; index++) {
            String name = index < parameters.length && parameters[index].isNamePresent()
                    ? parameters[index].getName() : "";
            if (isIdentifierName(name)) {
                String identifier = scalarIdentifier(arguments[index]);
                if (hasText(identifier)) return identifier;
            }
        }

        for (Object argument : arguments) {
            String identifier = findIdentifier(argument, null, 0, new IdentityHashMap<>());
            if (hasText(identifier)) return identifier;
        }

        String entity = entityType(serviceType);
        if (entity.contains("account") || entity.contains("user")) {
            for (Object argument : arguments) {
                String email = findNamedValue(argument, "email", 0);
                if (hasText(email)) return email;
            }
            for (int index = 0; index < arguments.length && index < parameters.length; index++) {
                if ("email".equalsIgnoreCase(parameters[index].getName())) {
                    String email = scalarIdentifier(arguments[index]);
                    if (hasText(email)) return email;
                }
            }
        }
        return "-";
    }

    public Map<String, Object> request(
            Class<?> serviceType, Method method, Object[] arguments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operation", operation(serviceType, method));
        snapshot.put("action", action(method).name());
        snapshot.put("input", arguments(method, arguments));
        snapshot.put("httpRequest", httpRequest());
        return snapshot;
    }

    public Map<String, Object> result(
            Class<?> serviceType, Method method, Object result, List<Map<String, Object>> nestedOperations) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operation", operation(serviceType, method));
        snapshot.put("outcome", "SUCCESS");
        snapshot.put("output", sanitize(result));
        if (!nestedOperations.isEmpty()) {
            snapshot.put("nestedOperations", List.copyOf(nestedOperations));
        }
        return snapshot;
    }

    public Map<String, Object> nestedRequest(Class<?> serviceType, Method method, Object[] arguments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operation", operation(serviceType, method));
        snapshot.put("action", action(method).name());
        snapshot.put("input", arguments(method, arguments));
        return snapshot;
    }

    public Map<String, Object> nestedSuccess(Map<String, Object> operation, Object result) {
        Map<String, Object> snapshot = new LinkedHashMap<>(operation);
        snapshot.put("outcome", "SUCCESS");
        snapshot.put("output", sanitize(result));
        return snapshot;
    }

    public Map<String, Object> nestedFailure(Map<String, Object> operation, Throwable failure) {
        Map<String, Object> snapshot = new LinkedHashMap<>(operation);
        snapshot.put("outcome", "FAILED");
        snapshot.put("errorType", failure.getClass().getSimpleName());
        return snapshot;
    }

    private Map<String, Object> arguments(Method method, Object[] arguments) {
        Map<String, Object> values = new LinkedHashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            if (isInfrastructureValue(argument)) continue;
            String name = index < parameters.length && parameters[index].isNamePresent()
                    ? parameters[index].getName() : "arg" + index;
            values.put(name, sanitize(argument));
        }
        return values;
    }

    private Object sanitize(Object value) {
        return sanitize(value, null, 0, new IdentityHashMap<>());
    }

    private Object sanitize(Object value, String propertyName, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return null;
        if (propertyName != null && isSensitiveName(propertyName)) return "[REDACTED]";
        if (isInfrastructureValue(value)) return "[OMITTED]";
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof TemporalAccessor || value instanceof UUID) return value.toString();
        if (value instanceof CharSequence text) return truncate(text.toString());
        if (value instanceof Number || value instanceof Boolean) return value;
        if (isInfrastructureValue(value)) return "[OMITTED]";
        if (depth >= MAX_DEPTH) return "[DEPTH_LIMIT]";

        if (seen.put(value, Boolean.TRUE) != null) return "[CIRCULAR]";
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_ITEMS) {
                        result.put("_truncated", true);
                        break;
                    }
                    String key = String.valueOf(entry.getKey());
                    result.put(key, sanitize(entry.getValue(), key, depth + 1, seen));
                }
                return result;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> result = new ArrayList<>();
                int count = 0;
                for (Object item : collection) {
                    if (count++ >= MAX_ITEMS) break;
                    result.add(sanitize(item, null, depth + 1, seen));
                }
                return result;
            }
            if (value.getClass().isArray()) {
                int length = Math.min(Array.getLength(value), MAX_ITEMS);
                List<Object> result = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    result.add(sanitize(Array.get(value, index), null, depth + 1, seen));
                }
                return result;
            }
            JsonNode tree = objectMapper.valueToTree(value);
            return sanitizeJson(tree, depth + 1);
        } catch (RuntimeException serializationFailure) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", value.getClass().getSimpleName());
            fallback.put("snapshot", "unavailable");
            return fallback;
        } finally {
            seen.remove(value);
        }
    }

    private Object sanitizeJson(JsonNode node, int depth) {
        if (node == null || node.isNull()) return null;
        if (depth >= MAX_DEPTH) return "[DEPTH_LIMIT]";
        if (node.isTextual()) return truncate(node.asText());
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (JsonNode child : node) {
                if (count++ >= MAX_ITEMS) break;
                result.add(sanitizeJson(child, depth + 1));
            }
            return result;
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            var fields = node.fields();
            int count = 0;
            while (fields.hasNext()) {
                if (count++ >= MAX_ITEMS) {
                    result.put("_truncated", true);
                    break;
                }
                var field = fields.next();
                result.put(field.getKey(), isSensitiveName(field.getKey())
                        ? "[REDACTED]" : sanitizeJson(field.getValue(), depth + 1));
            }
            return result;
        }
        return node.asText();
    }

    private String findIdentifier(Object value, String propertyName, int depth,
            IdentityHashMap<Object, Boolean> seen) {
        if (value == null || depth >= MAX_DEPTH) return null;
        if (propertyName != null && isIdentifierName(propertyName)) {
            String identifier = scalarIdentifier(value);
            if (hasText(identifier)) return identifier;
        }
        if (value instanceof Map<?, ?> map) {
            if (seen.put(value, Boolean.TRUE) != null) return null;
            try {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String identifier = findIdentifier(entry.getValue(), String.valueOf(entry.getKey()), depth + 1, seen);
                    if (hasText(identifier)) return identifier;
                }
            } finally {
                seen.remove(value);
            }
            return null;
        }
        if (value instanceof Collection<?> collection) {
            if (seen.put(value, Boolean.TRUE) != null) return null;
            try {
                for (Object item : collection) {
                    String identifier = findIdentifier(item, null, depth + 1, seen);
                    if (hasText(identifier)) return identifier;
                }
            } finally {
                seen.remove(value);
            }
            return null;
        }
        if (value instanceof CharSequence text) {
            String candidate = text.toString();
            return UUID_VALUE.matcher(candidate).matches() ? candidate : null;
        }
        if (isScalar(value)) return null;
        if (seen.put(value, Boolean.TRUE) != null) return null;
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            return findIdentifier(tree, null, depth + 1, seen);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            seen.remove(value);
        }
    }

    private String findIdentifier(JsonNode node, String propertyName, int depth,
            IdentityHashMap<Object, Boolean> seen) {
        if (node == null || depth >= MAX_DEPTH) return null;
        if (propertyName != null && isIdentifierName(propertyName) && node.isValueNode()) {
            String identifier = node.asText();
            if (hasText(identifier)) return identifier;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String identifier = findIdentifier(field.getValue(), field.getKey(), depth + 1, seen);
                if (hasText(identifier)) return identifier;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String identifier = findIdentifier(child, null, depth + 1, seen);
                if (hasText(identifier)) return identifier;
            }
        }
        return null;
    }

    private String findNamedValue(Object value, String name, int depth) {
        if (value == null || depth >= MAX_DEPTH) return null;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (name.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    String candidate = scalarIdentifier(entry.getValue());
                    if (hasText(candidate)) return candidate;
                }
                String nested = findNamedValue(entry.getValue(), name, depth + 1);
                if (hasText(nested)) return nested;
            }
            return null;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String nested = findNamedValue(item, name, depth + 1);
                if (hasText(nested)) return nested;
            }
            return null;
        }
        if (value instanceof JsonNode node) return findNamedValue(node, name, depth);
        if (isScalar(value)) return null;
        try {
            return findNamedValue(objectMapper.valueToTree(value), name, depth + 1);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String findNamedValue(JsonNode node, String name, int depth) {
        if (node == null || depth >= MAX_DEPTH) return null;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (name.equalsIgnoreCase(field.getKey()) && field.getValue().isValueNode()) {
                    String candidate = field.getValue().asText();
                    if (hasText(candidate)) return candidate;
                }
                String nested = findNamedValue(field.getValue(), name, depth + 1);
                if (hasText(nested)) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findNamedValue(child, name, depth + 1);
                if (hasText(nested)) return nested;
            }
        }
        return null;
    }

    private static String scalarIdentifier(Object value) {
        if (value instanceof CharSequence text && hasText(text.toString())) return truncate(text.toString());
        if (value instanceof Number number) return number.toString();
        return null;
    }

    private static boolean isIdentifierName(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.equals("id") || normalized.endsWith("id")
                || normalized.contains("transactionreference") || normalized.equals("reference");
    }

    private static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME.matcher(name.replaceAll("[^A-Za-z0-9]", "")).matches();
    }

    private static boolean isInfrastructureValue(Object value) {
        if (value == null) return false;
        String className = value.getClass().getName();
        return value instanceof MultipartFile
                || value instanceof ServletRequest
                || value instanceof ServletResponse
                || value instanceof java.security.Principal
                || className.startsWith("org.springframework.security.core.")
                || className.startsWith("org.springframework.validation.")
                || value instanceof java.io.InputStream
                || value instanceof java.io.OutputStream
                || value instanceof byte[];
    }

    private static Map<String, Object> httpRequest() {
        Map<String, Object> requestInfo = new LinkedHashMap<>();
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)) {
            return requestInfo;
        }
        jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
        requestInfo.put("method", request.getMethod());
        requestInfo.put("path", request.getRequestURI());
        String requestId = request.getHeader("X-Correlation-ID");
        if (requestId == null || requestId.isBlank()) requestId = request.getHeader("X-Request-ID");
        if (requestId != null && !requestId.isBlank()) {
            requestInfo.put("requestId", truncate(requestId));
        }
        return requestInfo;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isScalar(Object value) {
        return value instanceof Enum<?> || value instanceof TemporalAccessor || value instanceof UUID
                || value instanceof CharSequence || value instanceof Number || value instanceof Boolean;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_STRING_LENGTH
                ? value
                : value.substring(0, MAX_STRING_LENGTH) + "…";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
