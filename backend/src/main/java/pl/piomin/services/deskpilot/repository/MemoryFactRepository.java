package pl.piomin.services.deskpilot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.MemoryFact;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, Long> {

    List<MemoryFact> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<MemoryFact> findFirstByUserIdAndContentIgnoreCase(String userId, String content);
}
