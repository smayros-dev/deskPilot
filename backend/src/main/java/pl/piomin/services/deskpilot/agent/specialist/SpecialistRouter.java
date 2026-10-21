package pl.piomin.services.deskpilot.agent.specialist;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.ModelGateway;
import pl.piomin.services.deskpilot.config.SpecialistProperties;

/**
 * The supervisor (12f): decides which specialist handles a message, cheapest method first so routing costs almost
 * nothing on CPU:
 * <ol>
 *     <li><b>keywords</b>: one specialist's keywords appear in the message more than any other's;</li>
 *     <li><b>meaning</b>: the message is clearly closest (embeddings) to one specialist's description and examples;</li>
 *     <li><b>previous</b>: a follow-up in the same conversation ("still not working") stays with the last specialist;</li>
 *     <li><b>model</b> (optional, off by default): the chat model picks one;</li>
 *     <li>otherwise the general agent.</li>
 * </ol>
 * Routing never fails a run: if the embedding or the model call fails, the next method is tried.
 * <p>
 * The specialists are read from the catalog on every message, so an edit on the Specialists page (13g) applies to the
 * next message; only the changed specialist's description and examples are embedded again.
 */
@Component
public class SpecialistRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SpecialistRouter.class);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    /** nomic-embed-text expects a task prefix; the same one on both sides keeps the vectors comparable. */
    static final String EMBEDDING_PREFIX = "classification: ";

    public enum Method { KEYWORDS, MEANING, PREVIOUS, MODEL, DEFAULT }

    /**
     * @param confidence share of the keyword hits, or the similarity for routing by meaning; null otherwise
     * @param detail     why, for the trace (matched keywords, similarities...)
     * @param modelCalls 1 when the model was asked to choose, with the tokens it used
     */
    public record Routing(Specialist specialist, Method method, Double confidence, String detail,
                          int modelCalls, long promptTokens, long completionTokens) {

        static Routing of(Specialist specialist, Method method, Double confidence, String detail) {
            return new Routing(specialist, method, confidence, detail, 0, 0, 0);
        }
    }

    private final SpecialistCatalog catalog;
    private final EmbeddingModel embeddingModel;
    private final ModelGateway modelGateway;
    private final SpecialistProperties properties;
    private final Map<String, Pattern> keywordPatterns = new ConcurrentHashMap<>();
    /** Embeddings of each specialist's description and example questions, computed on first use. */
    private final Map<String, Vectors> exampleVectors = new ConcurrentHashMap<>();

    /** @param texts what was embedded, so a changed description or example is embedded again */
    private record Vectors(List<String> texts, List<float[]> vectors) {
    }

    /**
     * Why a message would be routed where it is, for tuning keywords on the Specialists page (13g).
     *
     * @param keywords     matched keywords per specialist
     * @param similarities closest description or example per specialist; empty when the embedding model is down
     */
    public record Preview(Routing routing, Map<String, List<String>> keywords, Map<String, Double> similarities) {
    }

    public SpecialistRouter(SpecialistCatalog catalog, EmbeddingModel embeddingModel, ModelGateway modelGateway,
                            SpecialistProperties properties) {
        this.catalog = catalog;
        this.embeddingModel = embeddingModel;
        this.modelGateway = modelGateway;
        this.properties = properties;
    }

    /**
     * @param previousSpecialist specialist of the conversation's last routed message, or null
     * @param config             settings of the run, used when the model has to choose
     */
    public Routing route(String message, String previousSpecialist, AgentConfig config) {
        Map<String, List<String>> hits = keywordHits(message);
        int best = hits.values().stream().mapToInt(List::size).max().orElse(0);
        List<String> leaders = hits.entrySet().stream().filter(e -> e.getValue().size() == best && best > 0)
                .map(Map.Entry::getKey).toList();
        if (leaders.size() == 1) {
            String id = leaders.getFirst();
            int total = hits.values().stream().mapToInt(List::size).sum();
            return Routing.of(specialist(id), Method.KEYWORDS, (double) best / total,
                    "keywords: " + String.join(", ", hits.get(id)) + otherHits(hits, id));
        }

        Optional<Routing> byMeaning = byMeaning(message, leaders, hits);
        if (byMeaning.isPresent()) {
            return byMeaning.get();
        }
        if (previousSpecialist != null) {
            Optional<Specialist> previous = catalog.find(previousSpecialist);
            if (previous.isPresent()) {
                return Routing.of(previous.get(), Method.PREVIOUS, null, "follow-up in the same conversation");
            }
        }
        if (properties.modelFallback()) {
            Optional<Routing> byModel = byModel(message, config);
            if (byModel.isPresent()) {
                return byModel.get();
            }
        }
        return Routing.of(Specialist.GENERAL, Method.DEFAULT, null,
                leaders.size() > 1 ? "keywords of several specialists: " + String.join(", ", leaders) : "no specialist matched");
    }

    /**
     * Like {@link #route}, for a message outside a conversation, with the keyword hits and similarities of every
     * specialist. The model is only asked when model fallback is on and nothing else decides, as in a real run.
     */
    public Preview preview(String message, AgentConfig config) {
        Routing routing = route(message, null, config);
        Map<String, Double> similarities = new LinkedHashMap<>();
        Map<String, List<float[]>> vectors = exampleVectors();
        if (!vectors.isEmpty()) {
            try {
                float[] question = embeddingModel.embed(EMBEDDING_PREFIX + message);
                vectors.forEach((id, list) -> similarities.put(id,
                        list.stream().mapToDouble(v -> cosine(question, v)).max().orElse(0)));
            } catch (RuntimeException e) {
                LOG.warn("Similarities skipped: {}", e.getMessage());
            }
        }
        return new Preview(routing, keywordHits(message), similarities);
    }

    /** Matched keywords per specialist, only for specialists with at least one. */
    Map<String, List<String>> keywordHits(String message) {
        String text = normalize(message);
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (Specialist specialist : catalog.specialists()) {
            List<String> matched = new ArrayList<>();
            for (String keyword : specialist.keywords()) {
                if (keywordPattern(keyword).matcher(text).find()) {
                    matched.add(keyword);
                }
            }
            if (!matched.isEmpty()) {
                hits.put(specialist.id(), matched);
            }
        }
        return hits;
    }

    /**
     * The specialist whose description or examples are closest to the message, when it is close enough and clearly
     * ahead of the next one. With a keyword tie, only the tied specialists compete.
     */
    private Optional<Routing> byMeaning(String message, List<String> tied, Map<String, List<String>> hits) {
        Map<String, List<float[]>> vectors = exampleVectors();
        if (vectors.isEmpty()) {
            return Optional.empty();
        }
        float[] question;
        try {
            question = embeddingModel.embed(EMBEDDING_PREFIX + message);
        } catch (RuntimeException e) {
            LOG.warn("Routing by meaning skipped: {}", e.getMessage());
            return Optional.empty();
        }
        record Score(String id, double similarity) {
        }
        List<Score> scores = vectors.entrySet().stream()
                .filter(e -> tied.isEmpty() || tied.contains(e.getKey()))
                .map(e -> new Score(e.getKey(), e.getValue().stream().mapToDouble(v -> cosine(question, v)).max().orElse(0)))
                .sorted(Comparator.comparingDouble(Score::similarity).reversed())
                .toList();
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        Score top = scores.getFirst();
        double second = scores.size() > 1 ? scores.get(1).similarity() : 0;
        String detail = scores.stream().map(s -> s.id() + " " + String.format(Locale.ROOT, "%.2f", s.similarity()))
                .collect(Collectors.joining(", "));
        if (top.similarity() < properties.minSimilarity() || top.similarity() - second < properties.minMargin()) {
            LOG.debug("Routing by meaning inconclusive: {}", detail);
            return Optional.empty();
        }
        String reason = tied.size() > 1 ? "keyword tie (" + String.join(", ", tied) + ") broken by meaning: " : "meaning: ";
        return Optional.of(Routing.of(specialist(top.id()), Method.MEANING, top.similarity(), reason + detail));
    }

    /**
     * Embeddings of each enabled specialist's description and example questions. A specialist is embedded again only
     * when these texts changed; vectors of deleted or disabled specialists are dropped.
     */
    private Map<String, List<float[]>> exampleVectors() {
        Map<String, List<float[]>> vectors = new LinkedHashMap<>();
        try {
            for (Specialist specialist : catalog.specialists()) {
                List<String> texts = new ArrayList<>();
                texts.add(EMBEDDING_PREFIX + specialist.description());
                specialist.examples().forEach(e -> texts.add(EMBEDDING_PREFIX + e.question()));
                Vectors cached = exampleVectors.get(specialist.id());
                if (cached == null || !cached.texts().equals(texts)) {
                    cached = new Vectors(texts, embeddingModel.embed(texts));
                    exampleVectors.put(specialist.id(), cached);
                }
                vectors.put(specialist.id(), cached.vectors());
            }
        } catch (RuntimeException e) {
            // Embedding model unavailable: route without meaning this time and try again next message.
            LOG.warn("Could not embed the specialists' examples: {}", e.getMessage());
            return Map.of();
        }
        exampleVectors.keySet().retainAll(vectors.keySet());
        return vectors;
    }

    private Pattern keywordPattern(String keyword) {
        return keywordPatterns.computeIfAbsent(keyword,
                k -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalize(k))));
    }

    /** Asks the chat model to name the specialist; only used when the cheaper methods are inconclusive. */
    private Optional<Routing> byModel(String message, AgentConfig config) {
        String choices = catalog.all().stream().map(s -> "- " + s.id() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        try {
            ChatResponse response = modelGateway.call(config, List.of(
                    new SystemMessage("Route the IT helpdesk request to one team. Reply with the team id only.\n" + choices),
                    new UserMessage(message)), List.of());
            String reply = response.getResult().getOutput().getText();
            String answer = THINK.matcher(reply == null ? "" : reply).replaceAll("").strip().toLowerCase(Locale.ROOT);
            Usage usage = response.getMetadata().getUsage();
            long promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            long completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            return catalog.all().stream()
                    .filter(s -> Pattern.compile("\\b" + Pattern.quote(s.id()) + "\\b").matcher(answer).find())
                    .findFirst()
                    .map(s -> new Routing(s, Method.MODEL, null, "chosen by the model", 1, promptTokens, completionTokens));
        } catch (RuntimeException e) {
            LOG.warn("Routing by the model failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Specialist specialist(String id) {
        return catalog.find(id).orElse(Specialist.GENERAL);
    }

    private static String otherHits(Map<String, List<String>> hits, String chosen) {
        String others = hits.entrySet().stream().filter(e -> !e.getKey().equals(chosen))
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("; "));
        return others.isEmpty() ? "" : " (also " + others + ")";
    }

    /** Lower case without accents, so "Réseau" matches the keyword "reseau" and the other way round. */
    static String normalize(String text) {
        return DIACRITICS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
