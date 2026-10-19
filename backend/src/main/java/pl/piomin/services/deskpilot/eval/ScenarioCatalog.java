package pl.piomin.services.deskpilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The evaluation scenarios, loaded once from {@code classpath:eval/scenarios.json}. */
@Component
public class ScenarioCatalog {

    private final List<Scenario> scenarios;

    public ScenarioCatalog(JsonMapper jsonMapper, @Value("classpath:eval/scenarios.json") Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.scenarios = List.copyOf(jsonMapper.readValue(in, new TypeReference<List<Scenario>>() { }));
        }
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : scenarios) {
            if (!ids.add(scenario.id()) || scenario.expect() == null || scenario.expect().outcome() == null) {
                throw new IllegalStateException("Invalid or duplicate scenario: " + scenario.id());
            }
        }
    }

    public List<Scenario> all() {
        return scenarios;
    }

    /** Scenarios of the given categories; all of them when none are given. */
    public List<Scenario> select(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return scenarios;
        }
        return scenarios.stream().filter(scenario -> categories.contains(scenario.category())).toList();
    }
}
