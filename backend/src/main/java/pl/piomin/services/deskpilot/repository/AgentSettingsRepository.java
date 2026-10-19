package pl.piomin.services.deskpilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.AgentSettings;

public interface AgentSettingsRepository extends JpaRepository<AgentSettings, Integer> {
}
