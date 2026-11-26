package io.graphspindle.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A versioned prompt template from {@code classpath:prompts/<id>.st} (ARCHITECTURE §7).
 *
 * <p>Placeholders are {@code {{name}}}. Rendering is a <b>single pass over the template text</b>:
 * substituted values are inserted verbatim and never scanned again, so untrusted content that
 * contains {@code {{schema}}} or StringTemplate syntax cannot expand anything. Every placeholder must
 * be supplied and no unknown value may be passed, so a renamed variable fails loudly instead of
 * silently sending an empty section to the model. Spring AI's {@code StTemplateRenderer} is not used
 * because its {@code {}} delimiters clash with the JSON schema and examples in the prompts.
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");

    private final String id;
    private final String text;
    private final Set<String> variables;

    private PromptTemplate(String id, String text) {
        this.id = id;
        this.text = text;
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            vars.add(m.group(1));
        }
        this.variables = Collections.unmodifiableSet(vars);
    }

    /** Loads {@code prompts/<id>.st} from the classpath, e.g. {@code reconcile-v1.system}. */
    public static PromptTemplate load(String id) {
        if (!id.matches("[a-z0-9][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("invalid prompt id " + id);
        }
        String path = "prompts/" + id + ".st";
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("prompt template not found: " + path);
            }
            return new PromptTemplate(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static PromptTemplate of(String id, String text) {
        return new PromptTemplate(id, Objects.requireNonNull(text, "text"));
    }

    public String id() {
        return id;
    }

    public Set<String> variables() {
        return variables;
    }

    public String render(Map<String, String> values) {
        for (String v : variables) {
            if (values.get(v) == null) {
                throw new IllegalArgumentException("prompt " + id + ": no value for {{" + v + "}}");
            }
        }
        for (String k : values.keySet()) {
            if (!variables.contains(k)) {
                throw new IllegalArgumentException("prompt " + id + ": unknown variable " + k);
            }
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 4096);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start()).append(values.get(m.group(1)));
            last = m.end();
        }
        return sb.append(text, last, text.length()).toString();
    }
}
