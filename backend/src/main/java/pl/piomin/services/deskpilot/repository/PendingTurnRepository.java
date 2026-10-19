package pl.piomin.services.deskpilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.PendingTurn;

public interface PendingTurnRepository extends JpaRepository<PendingTurn, String> {

    boolean existsBySessionId(String sessionId);
}
