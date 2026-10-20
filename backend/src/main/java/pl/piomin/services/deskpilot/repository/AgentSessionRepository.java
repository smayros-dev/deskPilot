package pl.piomin.services.deskpilot.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.model.AgentSession;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {

    /**
     * The user's conversations that have at least one message, most recent first; {@code text} (lower case, or empty)
     * matches the title or any question asked in the conversation.
     */
    @Query("""
            select s from AgentSession s
            where s.userId = :userId
              and exists (select r.id from AgentRun r where r.sessionId = s.id)
              and (:text = ''
                   or lower(coalesce(s.title, '')) like concat('%', :text, '%')
                   or exists (select r.id from AgentRun r where r.sessionId = s.id
                              and lower(r.userInput) like concat('%', :text, '%')))
            order by s.lastActivityAt desc""")
    List<AgentSession> findHistory(String userId, String text, Pageable page);

    List<AgentSession> findByUserIdOrderByCreatedAtAsc(String userId);

    /** Sessions started before the cutoff with no run after it (candidates for the retention clean-up). */
    @Query("""
            select s from AgentSession s where s.createdAt < :cutoff
              and not exists (select r.id from AgentRun r where r.sessionId = s.id and r.createdAt >= :cutoff)""")
    List<AgentSession> findInactiveSince(Instant cutoff);

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
