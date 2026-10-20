package pl.piomin.services.deskpilot.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.dto.UserDataExport;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentSession;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.MemoryFact;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentSessionRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;
import pl.piomin.services.deskpilot.repository.MemoryFactRepository;
import pl.piomin.services.deskpilot.repository.PendingTurnRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;
import pl.piomin.services.deskpilot.repository.TicketRepository;

/**
 * Personal data of a user (12o, GDPR): export everything stored about them, delete a conversation (with its runs,
 * trace, feedback, paused turn and short-term memory), delete their long-term memories, or all of it. Tickets stay:
 * they are records of the helpdesk, not of the conversation. A conversation whose run is working or waiting for a
 * reviewer cannot be deleted (409), so no decision is lost half-way. Every deletion is audited.
 */
@Service
@Transactional(readOnly = true)
public class PrivacyService {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyService.class);

    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final RunFeedbackRepository feedback;
    private final PendingTurnRepository pendingTurns;
    private final MemoryFactRepository memories;
    private final TicketRepository tickets;
    private final ChatMemory chatMemory;
    private final AuditService audit;
    private final int retentionDays;

    public PrivacyService(AgentSessionRepository sessions, AgentRunRepository runs, AgentStepRepository steps,
                          RunFeedbackRepository feedback, PendingTurnRepository pendingTurns, MemoryFactRepository memories,
                          TicketRepository tickets, ChatMemory chatMemory, AuditService audit,
                          @Value("${agent.retention.conversations-days:0}") int retentionDays) {
        this.sessions = sessions;
        this.runs = runs;
        this.steps = steps;
        this.feedback = feedback;
        this.pendingTurns = pendingTurns;
        this.memories = memories;
        this.tickets = tickets;
        this.chatMemory = chatMemory;
        this.audit = audit;
        this.retentionDays = retentionDays;
    }

    /** Deletes one of the user's conversations; other users' sessions answer 404. */
    @Transactional
    public void deleteConversation(String sessionId, String userId) {
        AgentSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Session " + sessionId + " not found"));
        List<AgentRun> sessionRuns = runs.findBySessionIdOrderByCreatedAtAsc(sessionId);
        ensureIdle(session, sessionRuns);
        int deletedRuns = delete(session, sessionRuns);
        audit.record(userId, AuditAction.CONVERSATION_DELETED, "session", sessionId, deletedRuns + " run(s) deleted");
    }

    @Transactional
    public void deleteMemory(long memoryId, String userId) {
        MemoryFact memory = memories.findById(memoryId)
                .filter(m -> m.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Memory " + memoryId + " not found"));
        memories.delete(memory);
        audit.record(userId, AuditAction.MEMORY_DELETED, "memory", String.valueOf(memoryId), null);
    }

    /** @return how many memories were deleted */
    @Transactional
    public int deleteMemories(String userId) {
        int deleted = memories.deleteByUserId(userId);
        audit.record(userId, AuditAction.MEMORIES_DELETED, "user", userId, deleted + " memory(ies) deleted");
        return deleted;
    }

    /** Deletes all conversations and memories of the user; nothing is deleted if one conversation is busy. */
    @Transactional
    public void deleteAllData(String userId) {
        List<AgentSession> userSessions = sessions.findByUserIdOrderByCreatedAtAsc(userId);
        Map<String, List<AgentRun>> runsBySession = userSessions.stream()
                .collect(Collectors.toMap(AgentSession::getId, s -> runs.findBySessionIdOrderByCreatedAtAsc(s.getId())));
        userSessions.forEach(s -> ensureIdle(s, runsBySession.get(s.getId())));
        int deletedRuns = userSessions.stream().mapToInt(s -> delete(s, runsBySession.get(s.getId()))).sum();
        int deletedMemories = memories.deleteByUserId(userId);
        audit.record(userId, AuditAction.USER_DATA_DELETED, "user", userId, userSessions.size() + " conversation(s), "
                + deletedRuns + " run(s) and " + deletedMemories + " memory(ies) deleted; tickets kept");
    }

    /** Everything stored about the user: conversations with questions and answers, memories, tickets requested. */
    public UserDataExport export(String userId) {
        List<UserDataExport.Conversation> conversations = sessions.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(session -> {
                    List<AgentRun> sessionRuns = runs.findBySessionIdOrderByCreatedAtAsc(session.getId());
                    Map<String, RunFeedback> ratings = feedback.findByRunIdIn(sessionRuns.stream().map(AgentRun::getId).toList())
                            .stream().collect(Collectors.toMap(RunFeedback::getRunId, Function.identity()));
                    return new UserDataExport.Conversation(session.getId(), session.getCreatedAt(), sessionRuns.stream()
                            .map(run -> new UserDataExport.Run(run.getId(), run.getCreatedAt(), run.getStatus(), run.getModel(),
                                    run.getUserInput(), run.getFinalAnswer(), feedbackOf(ratings.get(run.getId()))))
                            .toList());
                })
                .toList();
        List<UserDataExport.Memory> userMemories = memories.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(m -> new UserDataExport.Memory(m.getId(), m.getContent(), m.getCreatedAt()))
                .toList();
        List<UserDataExport.TicketRequested> requested = tickets.findByRequestedByOrderByCreatedAtDesc(userId).stream()
                .map(t -> new UserDataExport.TicketRequested(t.getId(), t.getTitle(), t.getDescription(), t.getPriority(),
                        t.getStatus(), t.getCreatedAt()))
                .toList();
        audit.record(userId, AuditAction.USER_DATA_EXPORTED, "user", userId, null);
        return new UserDataExport(userId, Instant.now(), conversations, userMemories, requested);
    }

    /**
     * Daily retention clean-up: when {@code agent.retention.conversations-days} is above 0, conversations with no
     * activity for that long are deleted (busy ones are skipped and retried the next day).
     */
    @Scheduled(cron = "${agent.retention.cron:0 30 3 * * *}")
    @Transactional
    public int applyRetention() {
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        for (AgentSession session : sessions.findInactiveSince(cutoff)) {
            List<AgentRun> sessionRuns = runs.findBySessionIdOrderByCreatedAtAsc(session.getId());
            if (isIdle(session, sessionRuns)) {
                delete(session, sessionRuns);
                deleted++;
            }
        }
        if (deleted > 0) {
            LOG.info("Retention: deleted {} conversation(s) inactive for {} days", deleted, retentionDays);
            audit.record(AuditService.SYSTEM, AuditAction.RETENTION_CLEANUP, null, null,
                    deleted + " conversation(s) inactive for " + retentionDays + " days deleted");
        }
        return deleted;
    }

    private int delete(AgentSession session, List<AgentRun> sessionRuns) {
        List<String> runIds = sessionRuns.stream().map(AgentRun::getId).toList();
        if (!runIds.isEmpty()) {
            steps.deleteByRunIdIn(runIds);
            feedback.deleteByRunIdIn(runIds);
        }
        pendingTurns.deleteBySessionId(session.getId());
        runs.deleteBySessionId(session.getId());
        chatMemory.clear(session.getId());
        sessions.delete(session);
        return runIds.size();
    }

    private static void ensureIdle(AgentSession session, List<AgentRun> sessionRuns) {
        if (!isIdle(session, sessionRuns)) {
            throw new ConflictException("Conversation " + session.getId()
                    + " has a request in progress or waiting for approval; try again when it is finished");
        }
    }

    private static boolean isIdle(AgentSession session, List<AgentRun> sessionRuns) {
        return session.getBusySince() == null && sessionRuns.stream().allMatch(AgentRun::isFinished);
    }

    private static UserDataExport.Feedback feedbackOf(RunFeedback rating) {
        return rating == null ? null : new UserDataExport.Feedback(rating.getRating(), rating.getComment(), rating.getUpdatedAt());
    }
}
