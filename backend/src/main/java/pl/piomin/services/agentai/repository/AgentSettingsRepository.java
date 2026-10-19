package pl.piomin.services.agentai.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.agentai.model.AgentSettings;

public interface AgentSettingsRepository extends JpaRepository<AgentSettings, Integer> {
}
