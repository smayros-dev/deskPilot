package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.PromptVersion;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    List<PromptVersion> findAllByOrderByCreatedAtDesc();

    boolean existsByName(String name);
}
