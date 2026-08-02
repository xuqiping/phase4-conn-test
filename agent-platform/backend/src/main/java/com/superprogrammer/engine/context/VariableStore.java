package com.superprogrammer.engine.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableStore {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)?)\\s*}}");

    private final Map<String, String> variables = new LinkedHashMap<>();

    public void set(String key, String value) {
        variables.put(key, value);
    }

    public String get(String key) {
        return variables.get(key);
    }

    public String renderTemplate(String template) {
        if (template == null) return null;
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.get(varName);
            matcher.appendReplacement(sb, replacement != null ? Matcher.quoteReplacement(replacement) : matcher.group(0));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public void clear() {
        variables.clear();
    }
}
