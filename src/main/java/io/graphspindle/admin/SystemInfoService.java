package io.graphspindle.admin;

import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import io.graphspindle.llm.IngestionLlm;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/v1/system/info} (IMPLEMENTATION_PLAN M10, ADR-035): version, the active LLM provider,
 * model and host, the vocabulary profile, and whether ingested content leaves this machine.
 */
@Service
public class SystemInfoService {

    static final String LOCAL_NOTICE = "Ingested content is processed by a local model and does not leave this "
            + "machine.";
    static final String REMOTE_NOTICE = "Ingested content (and snippets of this brain's existing knowledge used as"
            + " candidates) is sent to the LLM provider at %s for processing.";
    static final String NONE_NOTICE = "No LLM provider is configured: ingestion is unavailable and no content is"
            + " sent anywhere.";

    private final IngestionLlm llm;
    private final VocabularyRegistry vocabularies;
    private final Environment env;
    private final String version;

    public SystemInfoService(IngestionLlm llm, VocabularyRegistry vocabularies, Environment env,
            ObjectProvider<BuildProperties> build) {
        this.llm = llm;
        this.vocabularies = vocabularies;
        this.env = env;
        BuildProperties b = build.getIfAvailable();
        this.version = b != null ? b.getVersion() : env.getProperty("spring.ai.mcp.server.version", "unknown");
    }

    public record LlmInfo(String provider, String model, String host, boolean enabled, String structuredOutput) {
    }

    public record SystemInfo(String name, String version, LlmInfo llm, String vocabularyProfile,
            boolean dataLeavesMachine, String dataTransferNotice) {
    }

    /** Any authenticated principal. */
    public SystemInfo info(Actor actor) {
        BrainAccessGuard.requireAuthenticated(actor);
        String provider = llm.available() ? llm.provider() : "none";
        String host = host(provider);
        boolean leaves = leavesMachine(provider, host);
        String notice = "none".equals(provider) ? NONE_NOTICE
                : leaves ? REMOTE_NOTICE.formatted(host == null ? provider : host) : LOCAL_NOTICE;
        return new SystemInfo("GraphSpindle", version, new LlmInfo(provider, llm.available() ? llm.model() : null,
                host, llm.available(), llm.structuredOutputMode()), vocabularies.profile().name(), leaves, notice);
    }

    /** The provider's host (never a key or path). */
    String host(String provider) {
        String url = switch (provider) {
            case "ollama" -> env.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
            case "openai" -> env.getProperty("spring.ai.openai.base-url", "https://api.openai.com");
            case "anthropic" -> env.getProperty("spring.ai.anthropic.base-url", "https://api.anthropic.com");
            default -> null;
        };
        if (url == null) {
            return null;
        }
        try {
            String h = URI.create(url.strip()).getHost();
            return h == null ? null : h;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Conservative: only a loopback host counts as local; an unknown provider or host leaves the machine. */
    static boolean leavesMachine(String provider, String host) {
        if ("none".equals(provider)) {
            return false;
        }
        if (host == null) {
            return true;
        }
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return false;
        }
        if (!h.matches("[0-9.]+|[0-9a-fA-F:]+")) {
            return true; // a name: do not resolve it, assume remote
        }
        try {
            return !InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
