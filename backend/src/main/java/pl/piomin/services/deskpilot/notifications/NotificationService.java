package pl.piomin.services.deskpilot.notifications;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import pl.piomin.services.deskpilot.events.RunStatusChanged;
import pl.piomin.services.deskpilot.events.TicketCreated;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.service.RunService;
import pl.piomin.services.deskpilot.service.TicketService;

/**
 * E-mails (and webhook messages) for the moments a human has to act or wants to know (12j):
 * <ul>
 *     <li>a run waits for approval → the reviewers;</li>
 *     <li>a ticket was created → the person who asked for it;</li>
 *     <li>an approval waits longer than the SLA → the admins, once per run.</li>
 * </ul>
 * Built after the change is committed; evaluation runs publish no events, so they never notify anyone.
 */
@Service
public class NotificationService {

    static final int MAX_TEXT = 300;

    private final NotificationProperties properties;
    private final Messenger messenger;
    private final RunService runService;
    private final TicketService ticketService;
    private final ContactService contacts;
    private final ApprovalEscalationRepository escalations;

    public NotificationService(NotificationProperties properties, Messenger messenger, RunService runService,
                               TicketService ticketService, ContactService contacts,
                               ApprovalEscalationRepository escalations) {
        this.properties = properties;
        this.messenger = messenger;
        this.runService = runService;
        this.ticketService = ticketService;
        this.contacts = contacts;
        this.escalations = escalations;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onRunStatusChanged(RunStatusChanged event) {
        if (!properties.enabled()) {
            return;
        }
        AgentRun run = runService.get(event.runId());
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            return;
        }
        messenger.send(properties.reviewers(),
                "[DeskPilot] Approval needed: " + pendingActions(run) + " for " + run.getUserId(),
                """
                The agent wants to take an action that needs a reviewer's decision.

                User: %s
                Request: %s
                Action: %s

                Approve or reject it: %s/approvals
                Trace: %s/runs/%s
                """.formatted(run.getUserId(), shorten(run.getUserInput()), pendingDetails(run),
                        properties.baseUrl(), properties.baseUrl(), run.getId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onTicketCreated(TicketCreated event) {
        if (!properties.enabled()) {
            return;
        }
        Ticket ticket = ticketService.get(event.ticketId());
        Optional<String> email = contacts.emailOf(ticket.getRequestedBy());
        if (email.isEmpty()) {
            return;
        }
        messenger.send(List.of(email.get()),
                "[DeskPilot] Ticket #" + ticket.getId() + " created: " + shorten(ticket.getTitle()),
                """
                Your helpdesk ticket was created after a reviewer approved it.

                Ticket: #%d %s
                Priority: %s

                Your tickets: %s/helpdesk/tickets
                """.formatted(ticket.getId(), shorten(ticket.getTitle()), ticket.getPriority(), properties.baseUrl()));
    }

    /** Every minute: approvals waiting longer than the SLA are escalated to the admins, once per run. */
    @Scheduled(fixedDelayString = "${agent.notifications.sla-check-interval:PT1M}")
    public void escalateOverdueApprovals() {
        if (!properties.enabled() || properties.admins().isEmpty()) {
            return;
        }
        Instant deadline = Instant.now().minus(properties.approvalSla());
        for (AgentRun run : runService.findAwaitingApproval()) {
            Instant waitingSince = waitingSince(run);
            if (waitingSince.isAfter(deadline) || escalations.existsById(run.getId())) {
                continue;
            }
            try {
                escalations.saveAndFlush(new ApprovalEscalation(run.getId()));
            } catch (DataIntegrityViolationException alreadyEscalated) {
                continue;
            }
            messenger.send(properties.admins(),
                    "[DeskPilot] Approval overdue: " + pendingActions(run) + " for " + run.getUserId(),
                    """
                    A request has been waiting for a reviewer since %s (longer than %s).

                    User: %s
                    Request: %s
                    Action: %s

                    Approve or reject it: %s/approvals
                    Trace: %s/runs/%s
                    """.formatted(waitingSince, properties.approvalSla(), run.getUserId(), shorten(run.getUserInput()),
                            pendingDetails(run), properties.baseUrl(), properties.baseUrl(), run.getId()));
        }
    }

    /** When the run asked for approval (its latest request), or when it started. */
    private Instant waitingSince(AgentRun run) {
        return approvalRequests(run).stream()
                .map(AgentStep::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElse(run.getCreatedAt());
    }

    private String pendingActions(AgentRun run) {
        List<AgentStep> requests = approvalRequests(run);
        return requests.isEmpty() ? "an action"
                : requests.stream().map(AgentStep::getName).distinct().collect(Collectors.joining(", "));
    }

    private String pendingDetails(AgentRun run) {
        List<AgentStep> requests = approvalRequests(run);
        return requests.isEmpty() ? "-"
                : requests.stream().map(step -> step.getName() + " " + shorten(step.getContent()))
                .collect(Collectors.joining("; "));
    }

    private List<AgentStep> approvalRequests(AgentRun run) {
        List<AgentStep> steps = runService.steps(run.getId());
        // Only the requests after the last decision are still pending.
        int lastDecision = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getType() == StepType.APPROVAL_DECISION) {
                lastDecision = i;
            }
        }
        return steps.subList(lastDecision + 1, steps.size()).stream()
                .filter(step -> step.getType() == StepType.APPROVAL_REQUESTED)
                .toList();
    }

    static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_TEXT ? oneLine : oneLine.substring(0, MAX_TEXT) + "…";
    }
}
