package pl.piomin.services.deskpilot.agent.specialist;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The specialists defined in {@code specialists/specialists.json}, plus the general agent. */
@Component
public class SpecialistCatalog {

    private final List<Specialist> specialists;

    public SpecialistCatalog(JsonMapper jsonMapper,
                             @Value("classpath:specialists/specialists.json") Resource definitions) throws IOException {
        try (InputStream in = definitions.getInputStream()) {
            this.specialists = List.copyOf(jsonMapper.readValue(in, new TypeReference<List<Specialist>>() {
            }));
        }
        Set<String> ids = new HashSet<>();
        for (Specialist specialist : specialists) {
            if (specialist.id() == null || specialist.isGeneral() || !ids.add(specialist.id())) {
                throw new IllegalStateException("Invalid or duplicate specialist id: " + specialist.id());
            }
        }
    }

    /** The specialists a message can be routed to, without the general agent. */
    public List<Specialist> specialists() {
        return specialists;
    }

    /** The specialists followed by the general agent. */
    public List<Specialist> all() {
        List<Specialist> all = new ArrayList<>(specialists);
        all.add(Specialist.GENERAL);
        return all;
    }

    public Optional<Specialist> find(String id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
