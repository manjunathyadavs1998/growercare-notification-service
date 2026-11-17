package com.growercare.notification.domain;

import com.growercare.common.error.BusinessRuleException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Templates per language (SPEC-NOT-01 D5): {@code templates/<template>/<language>.txt} on the classpath, with
 * {@code {placeholder}} parameters. No fallback language: a missing text is a defect (R4). Texts are cached.
 */
public final class TemplateRenderer {

    private static final Pattern TEMPLATE_NAME = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    private final ClassLoader loader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private TemplateRenderer(ClassLoader loader) {
        this.loader = loader;
    }

    public static TemplateRenderer fromClasspath() {
        return new TemplateRenderer(TemplateRenderer.class.getClassLoader());
    }

    /** Renders a template; refuses (422) with {@code notification.template-unknown | language-missing | param-missing}. */
    public String render(String template, String language, Map<String, String> params) {
        if (template == null || !TEMPLATE_NAME.matcher(template).matches() || !exists(template + "/en.txt")) {
            throw new BusinessRuleException("notification.template-unknown", "Unknown template");
        }
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            throw new BusinessRuleException("notification.language-missing", "No text for this language");
        }
        var text = cache.computeIfAbsent(template + "/" + language, this::load);
        if (text.isEmpty()) {
            throw new BusinessRuleException("notification.language-missing", "Template " + template + " has no " + language + " text");
        }
        Matcher m = PLACEHOLDER.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            var value = params == null ? null : params.get(m.group(1));
            if (value == null) {
                throw new BusinessRuleException("notification.param-missing", "Parameter " + m.group(1) + " is required by " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    private boolean exists(String path) {
        return loader.getResource("templates/" + path) != null;
    }

    /** "" when the language has no text (cached, so a missing file is looked up once). */
    private String load(String path) {
        try (InputStream in = loader.getResourceAsStream("templates/" + path + ".txt")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read template " + path, e);
        }
    }
}
