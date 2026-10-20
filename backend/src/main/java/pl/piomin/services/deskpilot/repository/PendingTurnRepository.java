package pl.piomin.services.deskpilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.PendingTurn;

public interface PendingTurnRepository extends JpaRepository<PendingTurn, String> {

    boolean existsBySessionId(String sessionId);

    @Modifying
    @Query("delete from PendingTurn p where p.sessionId = :sessionId")
    int deleteBySessionId(String sessionId);
}
