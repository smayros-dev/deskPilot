package pl.piomin.services.deskpilot.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloud providers with an OpenAI-compatible API (12s). Mistral and Gemini are built in; their API key comes from an
 * environment variable or, failing that, from the key an admin stored in the UI (encrypted). Chat clients are created
 * at runtime and rebuilt when the key changes, so no restart is needed and the app starts without any key.
 */
@Service
@Transactional(readOnly = true)
public class CloudProviderService {

    /** Built-in providers; base URLs can be changed with {@code agent.providers.<id>.base-url}. */
    public record Definition(String id, String label, String defaultBaseUrl, String envVariable, String keysUrl) {
    }

    public static final List<Definition> DEFINITIONS = List.of(
            new Definition("mistral", "Mistral AI", "https://api.mistral.ai/v1", "MISTRAL_API_KEY",
                    "https://console.mistral.ai/api-keys"),
            new Definition("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "GEMINI_API_KEY", "https://aistudio.google.com/apikey"));

    public enum KeySource { ENVIRONMENT, STORED, NONE }

    /** What the UI may know about a provider: never the key itself. */
    public record Status(String id, String label, boolean enabled, KeySource keySource, String keyHint, String baseUrl,
                         String keysUrl, boolean available, String updatedBy, Instant updatedAt) {
    }

    public record CloudModel(String id, boolean supportsTools, boolean supportsVision) {
    }

    public record TestResult(boolean ok, long latencyMillis, int models, String error) {
    }

    private record CachedClient(String fingerprint, OpenAiChatModel model) {
    }

    private record CachedModels(String fingerprint, Instant at, List<CloudModel> models) {
    }

    private static final Duration MODELS_TTL = Duration.ofMinutes(10);

    private final CloudProviderRepository repository;
    private final SecretCipher cipher;
    private final Environment environment;
    private final ObservationRegistry observations;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, CachedModels> modelLists = new ConcurrentHashMap<>();

    public CloudProviderService(CloudProviderRepository repository, SecretCipher cipher, Environment environment,
                                ObservationRegistry observations, JsonMapper jsonMapper, ApplicationEventPublisher events) {
        this.repository = repository;
        this.cipher = cipher;
        this.environment = environment;
        this.observations = observations;
        this.jsonMapper = jsonMapper;
        this.events = events;
    }

    public static boolean isCloud(String provider) {
        return provider != null && !provider.isBlank() && !"ollama".equals(provider);
    }

    public List<Status> list() {
        return DEFINITIONS.stream().map(this::status).toList();
    }

    public Status get(String id) {
        return status(definition(id));
    }

    /** Enables/disables the provider and/or replaces or removes its stored key. */
    @Transactional
    public Status update(String id, Boolean enabled, String newKey, boolean removeKey, String actor) {
        Definition definition = definition(id);
        CloudProvider provider = repository.findById(id).orElseGet(() -> repository.save(new CloudProvider(id)));
        if (enabled != null) {
            provider.update(enabled, actor);
        }
        if (removeKey) {
            provider.removeKey(actor);
        } else if (newKey != null && !newKey.isBlank()) {
            String key = newKey.strip();
            provider.storeKey(cipher.encrypt(key), key.length() > 4 ? key.substring(key.length() - 4) : "", actor);
        }
        clients.remove(id);
        modelLists.remove(id);
        Status status = status(definition, provider);
        if (!status.available()) {
            // Whatever runs on it must stop now, e.g. a running A/B experiment (12m).
            events.publishEvent(new ProviderUnavailable(id, status.label()));
        }
        return status;
    }

    /** Enabled and with a key: the agent may use it. */
    public boolean isAvailable(String id) {
        return isCloud(id) && DEFINITIONS.stream().anyMatch(d -> d.id().equals(id)) && get(id).available();
    }

    /** Chat client for the provider, rebuilt when the key or base URL changed. */
    public ChatModel chatModel(String id) {
        Definition definition = definition(id);
        String key = availableKey(definition);
        String baseUrl = baseUrl(definition);
        String fingerprint = fingerprint(baseUrl, key);
        return clients.compute(id, (k, cached) -> cached != null && cached.fingerprint().equals(fingerprint)
                ? cached
                : new CachedClient(fingerprint, OpenAiChatModel.builder()
                        .options(OpenAiChatOptions.builder()
                                .baseUrl(baseUrl)
                                .apiKey(key)
                                .timeout(Duration.ofMinutes(2))
                                .maxRetries(1)
                                .build())
                        .observationRegistry(observations)
                        .build())).model();
    }

    /** Chat models offered by the provider for this key (cached for a few minutes). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<CloudModel> models(String id) {
        Definition definition = definition(id);
        String key = availableKey(definition);
        String fingerprint = fingerprint(baseUrl(definition), key);
        CachedModels cached = modelLists.get(id);
        if (cached != null && cached.fingerprint().equals(fingerprint) && cached.at().plus(MODELS_TTL).isAfter(Instant.now())) {
            return cached.models();
        }
        List<CloudModel> models = fetchModels(definition, key);
        modelLists.put(id, new CachedModels(fingerprint, Instant.now(), models));
        return models;
    }

    /** Checks the key by listing the models; never throws. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TestResult test(String id) {
        Definition definition = definition(id);
        long start = System.nanoTime();
        try {
            List<CloudModel> models = fetchModels(definition, availableKey(definition));
            return new TestResult(true, (System.nanoTime() - start) / 1_000_000, models.size(), null);
        } catch (RuntimeException e) {
            return new TestResult(false, (System.nanoTime() - start) / 1_000_000, 0, e.getMessage());
        }
    }

    private List<CloudModel> fetchModels(Definition definition, String key) {
        JsonNode body;
        try {
            body = jsonMapper.readTree(RestClient.create().get()
                    .uri(baseUrl(definition) + "/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            throw new IllegalArgumentException(definition.label() + " refused the request (HTTP " + e.getStatusCode().value()
                    + (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403 ? ": check the API key" : "") + ")");
        } catch (RestClientException e) {
            throw new IllegalArgumentException(definition.label() + " is not reachable: " + e.getMessage());
        }
        List<CloudModel> models = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            String modelId = node.path("id").asString("").replaceFirst("^models/", "");
            if (modelId.isBlank() || !isChatModel(definition.id(), modelId, node)) {
                continue;
            }
            JsonNode capabilities = node.path("capabilities");
            boolean tools = capabilities.isMissingNode() || capabilities.path("function_calling").asBoolean(true);
            boolean vision = capabilities.isMissingNode()
                    ? "gemini".equals(definition.id())
                    : capabilities.path("vision").asBoolean(false);
            if (models.stream().noneMatch(m -> m.id().equals(modelId))) {
                models.add(new CloudModel(modelId, tools, vision));
            }
        }
        models.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(models);
    }

    /** Leaves out embedding, speech, image-generation and other non-chat models. */
    private static boolean isChatModel(String provider, String modelId, JsonNode node) {
        String id = modelId.toLowerCase(Locale.ROOT);
        if (id.matches(".*(embed|moderation|ocr|tts|transcri|audio|imagen|veo|aqa|image-generation|live|learnlm).*")) {
            return false;
        }
        JsonNode capabilities = node.path("capabilities");
        if (!capabilities.isMissingNode() && capabilities.has("completion_chat")) {
            return capabilities.path("completion_chat").asBoolean(false);
        }
        return !"gemini".equals(provider) || id.startsWith("gemini") || id.startsWith("gemma");
    }

    private Status status(Definition definition) {
        return status(definition, repository.findById(definition.id()).orElse(null));
    }

    private Status status(Definition definition, CloudProvider stored) {
        String envKey = environment.getProperty(definition.envVariable(), "");
        KeySource source = !envKey.isBlank() ? KeySource.ENVIRONMENT
                : stored != null && stored.getApiKeyEncrypted() != null ? KeySource.STORED : KeySource.NONE;
        String hint = switch (source) {
            case ENVIRONMENT -> envKey.length() > 4 ? envKey.substring(envKey.length() - 4) : "";
            case STORED -> stored.getKeyHint();
            case NONE -> null;
        };
        boolean enabled = stored != null && stored.isEnabled();
        return new Status(definition.id(), definition.label(), enabled, source, hint, baseUrl(definition),
                definition.keysUrl(), enabled && source != KeySource.NONE,
                stored == null ? null : stored.getUpdatedBy(), stored == null ? null : stored.getUpdatedAt());
    }

    private String availableKey(Definition definition) {
        Status status = status(definition);
        if (!status.enabled()) {
            throw new IllegalArgumentException(definition.label() + " is disabled: an admin can enable it in Settings > Providers");
        }
        String envKey = environment.getProperty(definition.envVariable(), "");
        if (!envKey.isBlank()) {
            return envKey.strip();
        }
        CloudProvider stored = repository.findById(definition.id()).orElse(null);
        if (stored == null || stored.getApiKeyEncrypted() == null) {
            throw new IllegalArgumentException(definition.label() + " has no API key: add one in Settings > Providers or set "
                    + definition.envVariable());
        }
        return cipher.decrypt(stored.getApiKeyEncrypted());
    }

    private String baseUrl(Definition definition) {
        String url = environment.getProperty("agent.providers." + definition.id() + ".base-url", definition.defaultBaseUrl());
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Definition definition(String id) {
        return DEFINITIONS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Provider " + id + " not found"));
    }

    private static String fingerprint(String baseUrl, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((baseUrl + "|" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
