package pl.piomin.services.deskpilot.agent.specialist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built-in specialists of {@code specialists/specialists.json}: seeded into the database once (V22) and restored
 * from the Specialists page on request; at run time the definitions come from the database (13g).
 */
public final class SpecialistDefaults {

    static final String RESOURCE = "specialists/specialists.json";

    private SpecialistDefaults() {
    }

    public static List<Specialist> load(JsonMapper jsonMapper) {
        try (InputStream in = SpecialistDefaults.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found on the classpath");
            }
            List<Specialist> specialists = List.copyOf(jsonMapper.readValue(in, new TypeReference<List<Specialist>>() {
            }));
            Set<String> ids = new HashSet<>();
            for (Specialist specialist : specialists) {
                if (specialist.id() == null || specialist.isGeneral() || !ids.add(specialist.id())) {
                    throw new IllegalStateException("Invalid or duplicate specialist id: " + specialist.id());
                }
            }
            return specialists;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
