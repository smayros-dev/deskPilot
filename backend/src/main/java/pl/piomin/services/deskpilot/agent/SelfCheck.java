package pl.piomin.services.deskpilot.agent;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.service.RunService;

/**
 * Reflection pass (12g): a second, tool-less model call that checks the agent's work against the knowledge-base
 * articles it retrieved. Small models often find the right article and then leave out the key fact or take an action
 * the article says is not needed; checking a short draft against short articles is easier for them than writing it.
 */
@Component
class SelfCheck {

    static final String ANSWER_PROMPT = """
            You check a helpdesk answer against the knowledge-base articles it is based on.
            Reply with exactly OK if the draft is supported by the articles and contains the facts the user needs.
            Otherwise reply with REVISED: followed by the corrected answer. In the corrected answer: add the key facts \
            from the articles that answer the question (numbers, durations, addresses, where to go), remove claims the \
            articles do not support, keep it short, keep the user's language and keep the [n] citation markers.""";

    static final String TICKET_PROMPT = """
            Decide from the knowledge-base articles whether the user's request needs a helpdesk ticket.
            Reply TICKET if the articles say a ticket is needed, or if they do not say.
            Reply NO TICKET: followed by what the articles tell the user to do instead, only if they say this request \
            is handled without a ticket.""";

    /** Prefix of the tool result returned when a ticket is not needed; also marks that the check already ran. */
    static final String NO_TICKET = "Not created: the knowledge base says no ticket is needed for this request. "
            + "Answer the user from the article instead: ";

    private final ModelGateway modelGateway;
    private final RunService runService;
    private final AgentMetrics metrics;

    SelfCheck(ModelGateway modelGateway, RunService runService, AgentMetrics metrics) {
        this.modelGateway = modelGateway;
        this.runService = runService;
        this.metrics = metrics;
    }

    /** @return the corrected answer, or empty when the draft is fine (or the check gave nothing usable) */
    Optional<String> reviseAnswer(ActiveTurn turn, String articles, String draft) {
        String verdict = ask(turn, ANSWER_PROMPT,
                "Question:\n" + turn.userInput() + "\n\nArticles:\n" + articles + "\n\nDraft answer:\n" + draft);
        if (verdict.isEmpty() || verdict.toUpperCase(Locale.ROOT).startsWith("OK")) {
            return Optional.empty();
        }
        int marker = verdict.toUpperCase(Locale.ROOT).indexOf("REVISED:");
        String revised = (marker >= 0 ? verdict.substring(marker + "REVISED:".length()) : verdict).strip();
        return revised.isEmpty() ? Optional.empty() : Optional.of(revised);
    }

    /** @return what the articles say to do instead, when they say no ticket is needed */
    Optional<String> ticketNotNeeded(ActiveTurn turn, String articles, String ticketArguments) {
        String verdict = ask(turn, TICKET_PROMPT,
                "Request:\n" + turn.userInput() + "\n\nTicket the agent wants to create:\n" + ticketArguments
                        + "\n\nArticles:\n" + articles);
        String upper = verdict.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("NO TICKET")) {
            return Optional.empty();
        }
        String instead = verdict.substring("NO TICKET".length()).replaceFirst("^\\s*:?\\s*", "").strip();
        return Optional.of(instead.isEmpty() ? "see the article" : instead);
    }

    private String ask(ActiveTurn turn, String instructions, String material) {
        AgentConfig config = turn.context().config();
        long start = System.nanoTime();
        ChatResponse response = modelGateway.call(config, List.of(new SystemMessage(instructions), new UserMessage(material)), List.of());
        long durationMillis = (System.nanoTime() - start) / 1_000_000;
        Usage usage = response.getMetadata().getUsage();
        long promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        runService.recordModelCall(turn.runId(), promptTokens, completionTokens);
        turn.countCheckCall();
        metrics.modelCall(durationMillis, promptTokens, completionTokens);
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        return text == null ? "" : text.strip();
    }
}
