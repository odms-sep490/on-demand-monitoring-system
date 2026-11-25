package com.ondemandmonitoring.prompt;

import com.ondemandmonitoring.prompt.PromptDtos.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Safe, restricted {{variable}} template engine.
 * Never evaluates expressions, code, SQL, or shell commands.
 */
@Component
public class PromptTemplateRenderer {
    public static final int MAX_RENDERED_CHARS = 32_000;
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(AKIA[0-9A-Z]{16}|-----BEGIN (?:RSA )?PRIVATE KEY-----)");

    public List<String> discoverVariables(String template) {
        if (template == null || template.isEmpty()) return List.of();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(template);
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.copyOf(found);
    }

    public ValidationResult validate(String template, List<VariableSpec> schema, Map<String, Object> suppliedVariables) {
        List<String> errors = new ArrayList<>();
        if (template == null || template.isBlank()) {
            errors.add("Nội dung prompt không được để trống.");
        }
        String safeTemplate = template == null ? "" : template;
        if (SECRET_PATTERN.matcher(safeTemplate).find()) {
            errors.add("Không được lưu credentials hoặc private key bên trong prompt.");
        }
        List<String> discovered = discoverVariables(safeTemplate);
        Map<String, VariableSpec> schemaByName = new LinkedHashMap<>();
        if (schema != null) {
            for (VariableSpec spec : schema) {
                if (spec != null && spec.name() != null) {
                    schemaByName.put(spec.name(), spec);
                }
            }
        }
        List<String> unknown = new ArrayList<>();
        if (!schemaByName.isEmpty()) {
            for (String v : discovered) {
                if (!schemaByName.containsKey(v)) {
                    unknown.add(v);
                }
            }
        }
        List<String> missingRequired = new ArrayList<>();
        if (suppliedVariables != null) {
            for (VariableSpec spec : schemaByName.values()) {
                if (spec.required()) {
                    Object val = suppliedVariables.get(spec.name());
                    if (val == null || val.toString().isBlank()) {
                        missingRequired.add(spec.name());
                    }
                }
            }
            if (!missingRequired.isEmpty()) {
                errors.add("Thiếu biến bắt buộc: " + String.join(", ", missingRequired));
            }
        }
        if (safeTemplate.length() > MAX_RENDERED_CHARS) {
            errors.add("Prompt vượt quá giới hạn " + MAX_RENDERED_CHARS + " ký tự.");
        }
        int estTokens = estimateTokens(safeTemplate);
        return new ValidationResult(
                errors.isEmpty(),
                discovered,
                missingRequired,
                unknown,
                errors,
                safeTemplate.length(),
                estTokens
        );
    }

    public PreviewResult render(String template, List<VariableSpec> schema, Map<String, Object> variables) {
        Map<String, Object> safeVars = variables == null ? Map.of() : variables;
        ValidationResult validation = validate(template, schema, safeVars);
        if (!validation.missingRequiredVariables().isEmpty()) {
            throw new IllegalArgumentException("Thiếu biến bắt buộc: " + String.join(", ", validation.missingRequiredVariables()));
        }
        String safeTemplate = template == null ? "" : template;
        Matcher m = VAR_PATTERN.matcher(safeTemplate);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = safeVars.get(key);
            String replacement = val == null ? "" : val.toString();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String rendered = sb.toString();
        if (rendered.length() > MAX_RENDERED_CHARS) {
            throw new IllegalArgumentException("Prompt sau khi render vượt quá giới hạn " + MAX_RENDERED_CHARS + " ký tự.");
        }
        ValidationResult finalVal = new ValidationResult(
                validation.valid(),
                validation.discoveredVariables(),
                validation.missingRequiredVariables(),
                validation.unknownVariables(),
                validation.errors(),
                rendered.length(),
                estimateTokens(rendered)
        );
        return new PreviewResult(rendered, finalVal);
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Conservative estimate (~3.5 chars per token for Vietnamese + English mix).
        return Math.max(1, (int) Math.ceil(text.length() / 3.5));
    }
}
