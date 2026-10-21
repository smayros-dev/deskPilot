package pl.piomin.services.deskpilot.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.piomin.services.deskpilot.agent.specialist.Specialist;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistCatalog;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistDefaults;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.SpecialistDefinition;
import pl.piomin.services.deskpilot.repository.SpecialistRepository;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specialist agents edited on the Specialists page (13g). Every change reloads the catalog once the transaction is
 * committed, so the next message is routed with it; runs already started or paused keep the prompt and tools they
 * were given.
 */
@Service
@Transactional(readOnly = true)
public class SpecialistService {

    /** Lower-case letters, digits and dashes, starting with a letter: used in traces, metrics and URLs. */
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,39}");
    /** External MCP tools come and go with their servers, so patterns for them are accepted as they are. */
    private static final String EXTERNAL_PREFIX = "mcp_";
    static final int MAX_KEYWORDS = 60;
    static final int MAX_KEYWORD_LENGTH = 60;
    static final int MAX_EXAMPLES = 20;

    /** A stored specialist with what the catalog does not need. */
    public record Definition(Specialist specialist, boolean enabled, int sortOrder, String updatedBy, Instant updatedAt) {
    }

    /** What an admin edits; {@code tools} null means every tool. */
    public record Values(String name, String description, List<String> keywords, List<String> tools, String prompt,
                         List<Specialist.Example> examples, boolean enabled, int sortOrder) {
    }

    private final SpecialistRepository repository;
    private final SpecialistCatalog catalog;
    private final ToolRegistry toolRegistry;
    private final JsonMapper jsonMapper;

    public SpecialistService(SpecialistRepository repository, SpecialistCatalog catalog, ToolRegistry toolRegistry,
                             JsonMapper jsonMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.toolRegistry = toolRegistry;
        this.jsonMapper = jsonMapper;
    }

    /** All stored specialists, disabled ones included, in routing order. */
    public List<Definition> list() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toDefinition).toList();
    }

    public Definition get(String id) {
        return toDefinition(find(id));
    }

    /** @throws ConflictException if the id is taken (or is the general agent's) */
    @Transactional
    public Definition create(String id, Values values, String actor) {
        String slug = id == null ? "" : id.strip();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "The id must be 2 to 40 lower-case letters, digits or dashes, starting with a letter");
        }
        if (Specialist.GENERAL_ID.equals(slug) || repository.existsById(slug)) {
            throw new ConflictException("A specialist with the id '" + slug + "' already exists");
        }
        SpecialistDefinition definition = new SpecialistDefinition(slug);
        apply(definition, values, actor);
        Definition saved = toDefinition(repository.save(definition));
        reloadAfterCommit();
        return saved;
    }

    @Transactional
    public Definition update(String id, Values values, String actor) {
        SpecialistDefinition definition = find(id);
        apply(definition, values, actor);
        reloadAfterCommit();
        return toDefinition(definition);
    }

    /** @throws ConflictException for the general agent, which handles everything the specialists do not */
    @Transactional
    public Definition delete(String id) {
        SpecialistDefinition definition = find(id);
        Definition deleted = toDefinition(definition);
        repository.delete(definition);
        reloadAfterCommit();
        return deleted;
    }

    /** Replaces every specialist with the built-in ones of {@code specialists/specialists.json}. */
    @Transactional
    public List<Definition> restoreDefaults(String actor) {
        repository.deleteAllInBatch();
        List<Specialist> defaults = SpecialistDefaults.load(jsonMapper);
        for (int i = 0; i < defaults.size(); i++) {
            Specialist s = defaults.get(i);
            SpecialistDefinition definition = new SpecialistDefinition(s.id());
            definition.update(s.name(), s.description(), jsonMapper.writeValueAsString(s.keywords()),
                    s.tools() == null ? null : jsonMapper.writeValueAsString(s.tools()), s.prompt(),
                    jsonMapper.writeValueAsString(s.examples()), true, (i + 1) * 10, actor);
            repository.save(definition);
        }
        reloadAfterCommit();
        return list();
    }

    private SpecialistDefinition find(String id) {
        if (Specialist.GENERAL_ID.equals(id)) {
            throw new ConflictException("The general agent is built in and cannot be changed or deleted");
        }
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Specialist " + id + " not found"));
    }

    private void apply(SpecialistDefinition definition, Values values, String actor) {
        List<String> keywords = keywords(values.keywords());
        List<Specialist.Example> examples = examples(values.examples());
        if (keywords.isEmpty() && examples.isEmpty()) {
            throw new IllegalArgumentException("Give at least one keyword or one example, or the specialist is never chosen");
        }
        List<String> tools = tools(values.tools());
        definition.update(values.name().strip(), values.description().strip(), jsonMapper.writeValueAsString(keywords),
                tools == null ? null : jsonMapper.writeValueAsString(tools), values.prompt().strip(),
                jsonMapper.writeValueAsString(examples), values.enabled(), values.sortOrder(), actor);
    }

    /** Trimmed, without blanks and duplicates (ignoring case), in the order given. */
    private static List<String> keywords(List<String> keywords) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String keyword : keywords == null ? List.<String>of() : keywords) {
            String trimmed = keyword == null ? "" : keyword.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                throw new IllegalArgumentException("Keyword too long (max " + MAX_KEYWORD_LENGTH + " characters): " + trimmed);
            }
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        if (unique.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("At most " + MAX_KEYWORDS + " keywords");
        }
        return List.copyOf(unique.values());
    }

    private static List<Specialist.Example> examples(List<Specialist.Example> examples) {
        List<Specialist.Example> cleaned = new ArrayList<>();
        for (Specialist.Example example : examples == null ? List.<Specialist.Example>of() : examples) {
            String question = example == null || example.question() == null ? "" : example.question().strip();
            String approach = example == null || example.approach() == null ? "" : example.approach().strip();
            if (question.isEmpty() && approach.isEmpty()) {
                continue;
            }
            if (question.isEmpty() || approach.isEmpty()) {
                throw new IllegalArgumentException("Each example needs a question and how it is handled");
            }
            if (question.length() > 300 || approach.length() > 500) {
                throw new IllegalArgumentException("Example too long (question max 300, approach max 500 characters)");
            }
            cleaned.add(new Specialist.Example(question, approach));
        }
        if (cleaned.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("At most " + MAX_EXAMPLES + " examples");
        }
        return cleaned;
    }

    /** Each pattern must name an installed tool or, ending in {@code *}, the prefix of one; null keeps every tool. */
    private List<String> tools(List<String> patterns) {
        if (patterns == null) {
            return null;
        }
        Set<String> known = toolRegistry.names();
        List<String> cleaned = new ArrayList<>();
        for (String pattern : patterns) {
            String trimmed = pattern == null ? "" : pattern.strip();
            if (trimmed.isEmpty() || cleaned.contains(trimmed)) {
                continue;
            }
            boolean prefix = trimmed.endsWith("*");
            String stem = prefix ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            boolean matches = stem.startsWith(EXTERNAL_PREFIX)
                    || known.stream().anyMatch(tool -> prefix ? tool.startsWith(stem) : tool.equals(stem));
            if (stem.isEmpty() || !matches) {
                throw new IllegalArgumentException("No tool matches '" + trimmed + "'");
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    private void reloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    catalog.invalidate();
                }
            });
        } else {
            catalog.invalidate();
        }
    }

    private Definition toDefinition(SpecialistDefinition definition) {
        return new Definition(SpecialistCatalog.toSpecialist(definition, jsonMapper), definition.isEnabled(),
                definition.getSortOrder(), definition.getUpdatedBy(), definition.getUpdatedAt());
    }
}
