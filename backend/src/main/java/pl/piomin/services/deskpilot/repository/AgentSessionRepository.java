package pl.piomin.services.deskpilot.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.model.AgentSession;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {

    /** Marks the session busy unless another request holds it; a claim older than {@code staleBefore} is taken over. */
    @Transactional
    @Modifying
    @Query("update AgentSession s set s.busySince = :now where s.id = :id and (s.busySince is null or s.busySince < :staleBefore)")
    int claim(String id, Instant now, Instant staleBefore);

    @Transactional
    @Modifying
    @Query("update AgentSession s set s.busySince = null where s.id = :id")
    int release(String id);

    @Transactional
    @Modifying
    @Query("update AgentSession s set s.busySince = null where s.busySince is not null")
    int releaseAll();
}
