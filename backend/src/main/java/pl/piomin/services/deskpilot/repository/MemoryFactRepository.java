package pl.piomin.services.deskpilot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.MemoryFact;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, Long> {

    List<MemoryFact> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("delete from MemoryFact m where m.userId = :userId")
    int deleteByUserId(String userId);

    Optional<MemoryFact> findFirstByUserIdAndContentIgnoreCase(String userId, String content);
}
