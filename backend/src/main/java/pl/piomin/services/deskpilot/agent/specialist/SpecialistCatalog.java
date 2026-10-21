package pl.piomin.services.deskpilot.agent.specialist;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.model.SpecialistDefinition;
import pl.piomin.services.deskpilot.repository.SpecialistRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The enabled specialists, from the {@code specialist} table (13g), plus the built-in general agent. The list is
 * cached until a specialist is changed on the Specialists page ({@link #invalidate()}).
 */
@Component
public class SpecialistCatalog {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Specialist.Example>> EXAMPLES = new TypeReference<>() {
    };

    private final Supplier<List<Specialist>> source;
    private volatile List<Specialist> cached;

    @Autowired
    public SpecialistCatalog(SpecialistRepository repository, JsonMapper jsonMapper) {
        this(() -> repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(SpecialistDefinition::isEnabled)
                .map(definition -> toSpecialist(definition, jsonMapper))
                .toList());
    }

    /** A catalog over fixed definitions, e.g. {@link SpecialistDefaults} in tests. */
    public SpecialistCatalog(Supplier<List<Specialist>> source) {
        this.source = source;
    }

    /** The specialists a message can be routed to, without the general agent. */
    public List<Specialist> specialists() {
        List<Specialist> specialists = cached;
        if (specialists == null) {
            specialists = List.copyOf(source.get());
            cached = specialists;
        }
        return specialists;
    }

    /** The specialists followed by the general agent. */
    public List<Specialist> all() {
        List<Specialist> all = new ArrayList<>(specialists());
        all.add(Specialist.GENERAL);
        return all;
    }

    public Optional<Specialist> find(String id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Reloads the definitions on next use; called after a specialist was created, changed or deleted. */
    public void invalidate() {
        cached = null;
    }

    public static Specialist toSpecialist(SpecialistDefinition definition, JsonMapper jsonMapper) {
        return new Specialist(definition.getId(), definition.getName(), definition.getDescription(),
                jsonMapper.readValue(definition.getKeywords(), STRINGS),
                definition.getTools() == null ? null : jsonMapper.readValue(definition.getTools(), STRINGS),
                definition.getPrompt(),
                jsonMapper.readValue(definition.getExamples(), EXAMPLES));
    }
}
